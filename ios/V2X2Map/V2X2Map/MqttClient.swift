import Foundation
import Network

/// Minimal MQTT 3.1.1 publisher over TCP/TLS using Network.framework — no external deps.
/// Mirrors the Android `MqttBridge`: publishes raw frame payloads to `its/<node>/packet`,
/// retained online/offline status to `its/<node>/status`, with a Last-Will of "offline".
/// Anonymous publish; TLS validated against the system trust store.
final class MqttClient {
    struct Config {
        var host: String
        var port: UInt16
        var tls: Bool
        var clientId: String
        var nodeId: String
    }

    private(set) var isConnected = false
    var onState: ((Bool) -> Void)?

    private let queue = DispatchQueue(label: "org.opentrafficmap.mqtt")
    private var conn: NWConnection?
    private var cfg: Config?
    private var wantUp = false
    private var rx = [UInt8]()
    private var pingTimer: DispatchSourceTimer?
    private var reconnectScheduled = false
    private let keepAlive = 60

    private var packetTopic: String { "its/\(cfg?.nodeId ?? "")/packet" }
    private var statusTopic: String { "its/\(cfg?.nodeId ?? "")/status" }

    func start(_ config: Config) {
        queue.async {
            self.cfg = config
            self.wantUp = true
            self.openConnection()
        }
    }

    func stop() {
        queue.async {
            self.wantUp = false
            if self.isConnected {
                self.rawSend(self.publishPacket(topic: self.statusTopic, payload: Array("offline".utf8), retain: true))
                self.rawSend([0xE0, 0x00])   // DISCONNECT
            }
            self.teardown()
        }
    }

    func publish(_ payload: [UInt8]) {
        queue.async {
            guard self.isConnected else { return }
            self.rawSend(self.publishPacket(topic: self.packetTopic, payload: payload, retain: false))
        }
    }

    // MARK: connection

    private func openConnection() {
        guard let cfg else { return }
        let params = cfg.tls
            ? NWParameters(tls: NWProtocolTLS.Options(), tcp: NWProtocolTCP.Options())
            : NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        guard let port = NWEndpoint.Port(rawValue: cfg.port) else { return }
        let c = NWConnection(host: NWEndpoint.Host(cfg.host), port: port, using: params)
        conn = c
        c.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.rx.removeAll()
                self.rawSend(self.connectPacket())
                self.receive()
            case .failed, .cancelled:
                self.teardown()
                if self.wantUp { self.scheduleReconnect() }
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func teardown() {
        pingTimer?.cancel(); pingTimer = nil
        conn?.cancel(); conn = nil
        if isConnected {
            isConnected = false
            onState?(false)
        }
    }

    private func scheduleReconnect() {
        guard !reconnectScheduled else { return }
        reconnectScheduled = true
        queue.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self else { return }
            self.reconnectScheduled = false
            if self.wantUp { self.openConnection() }
        }
    }

    private func receive() {
        conn?.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty { self.rx.append(contentsOf: data); self.parse() }
            if error != nil || isComplete {
                self.teardown()
                if self.wantUp { self.scheduleReconnect() }
                return
            }
            self.receive()
        }
    }

    private func parse() {
        while rx.count >= 2 {
            guard let (remLen, lenBytes) = decodeRemaining(rx, from: 1) else { return }
            let total = 1 + lenBytes + remLen
            guard rx.count >= total else { return }
            let type = rx[0] >> 4
            let body = Array(rx[(1 + lenBytes)..<total])
            rx.removeFirst(total)
            if type == 2 {   // CONNACK
                let rc = body.count >= 2 ? body[1] : 0xFF
                if rc == 0 { onConnected() } else { teardown() }
            }
            // PINGRESP (13) and others are ignored.
        }
    }

    private func onConnected() {
        isConnected = true
        onState?(true)
        rawSend(publishPacket(topic: statusTopic, payload: Array("online".utf8), retain: true))
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + Double(keepAlive) / 2, repeating: Double(keepAlive) / 2)
        t.setEventHandler { [weak self] in
            guard let self, self.isConnected else { return }
            self.rawSend([0xC0, 0x00])   // PINGREQ
        }
        pingTimer = t
        t.resume()
    }

    private func rawSend(_ bytes: [UInt8]) {
        conn?.send(content: Data(bytes), completion: .contentProcessed { _ in })
    }

    // MARK: packet building

    private func connectPacket() -> [UInt8] {
        guard let cfg else { return [] }
        var vh: [UInt8] = mqttString("MQTT")
        vh += [0x04]                                   // protocol level 3.1.1
        vh += [0x26]                                   // flags: cleanSession|will|willRetain (willQoS 0)
        vh += [UInt8(keepAlive >> 8), UInt8(keepAlive & 0xFF)]
        var payload: [UInt8] = mqttString(cfg.clientId)
        payload += mqttString(statusTopic)             // will topic
        payload += mqttBytes(Array("offline".utf8))    // will message
        return fixedHeader(type: 1, flags: 0, body: vh + payload)
    }

    private func publishPacket(topic: String, payload: [UInt8], retain: Bool) -> [UInt8] {
        var body = mqttString(topic)                   // QoS 0 → no packet identifier
        body += payload
        return fixedHeader(type: 3, flags: retain ? 0x01 : 0x00, body: body)
    }

    private func fixedHeader(type: UInt8, flags: UInt8, body: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [(type << 4) | flags]
        out += encodeRemaining(body.count)
        out += body
        return out
    }

    private func mqttString(_ s: String) -> [UInt8] { mqttBytes(Array(s.utf8)) }

    private func mqttBytes(_ b: [UInt8]) -> [UInt8] {
        [UInt8((b.count >> 8) & 0xFF), UInt8(b.count & 0xFF)] + b
    }

    private func encodeRemaining(_ len: Int) -> [UInt8] {
        var x = len
        var out: [UInt8] = []
        repeat {
            var byte = UInt8(x % 128)
            x /= 128
            if x > 0 { byte |= 0x80 }
            out.append(byte)
        } while x > 0
        return out
    }

    private func decodeRemaining(_ buf: [UInt8], from: Int) -> (value: Int, bytes: Int)? {
        var multiplier = 1, value = 0, i = from
        while true {
            guard i < buf.count else { return nil }
            let b = buf[i]; i += 1
            value += Int(b & 0x7F) * multiplier
            if b & 0x80 == 0 { break }
            multiplier *= 128
            if multiplier > 128 * 128 * 128 { return nil }
        }
        return (value, i - from)
    }
}
