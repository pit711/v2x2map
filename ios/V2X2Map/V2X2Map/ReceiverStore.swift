import Foundation
import CoreLocation

/// One station (vehicle / RSU) keyed by GeoNetworking address, with a short ring of its
/// most recent frames — drives both the map markers and the grouped log.
struct Station: Identifiable {
    let id: UInt64
    var lat: Double = 0
    var lon: Double = 0
    var hasPosition = false
    var msgType: MsgType = .unknown
    var headingDeg: Double?
    var speedMps: Double?
    var stationType: Int?
    var secured = false
    var count: Int = 0
    var lastSeq: Int64 = 0
    var recent: [Frame] = []

    init(id: UInt64) { self.id = id }

    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }

    var macLabel: String {
        (2..<8).map { i in String(format: "%02X", UInt8((id >> (UInt64(7 - i) * 8)) & 0xFF)) }
            .joined(separator: ":")
    }

    var typeName: String {
        switch stationType {
        case 5:  return "Pkw"
        case 6:  return "Lkw"
        case 10: return "Sondereinsatz"
        case 15: return "RSU"
        default: return "Station"
        }
    }

    mutating func update(with f: Frame) {
        msgType = f.msgType
        if let p = f.latLon {
            lat = p.lat; lon = p.lon; hasPosition = true
            headingDeg = f.headingDeg
            speedMps = f.speedMps
        }
        if let st = f.stationType { stationType = st }
        if f.secured == true { secured = true }
        lastSeq = f.seq
        count += 1
        recent.insert(f, at: 0)
        if recent.count > 20 { recent.removeLast(recent.count - 20) }
    }
}

/// Owns the BLE link, the frame decoder, GPS, geiger, PCAP recording, MQTT, replay and
/// notifications, and publishes everything the UI renders.
final class ReceiverStore: NSObject, ObservableObject {
    @Published var state: BleState = .idle
    @Published var statusText: String = "Bereit"
    @Published var recentFrames: [Frame] = []
    @Published var stations: [Station] = []
    @Published var totalFrames: Int = 0
    @Published var typeCounts: [MsgType: Int] = [:]
    @Published var config: CycleConfig?
    @Published var bleOn = false
    @Published var isReplaying = false
    @Published var recordingURL: URL?
    @Published var mqttConnected = false

    let settings = AppSettings()
    let location = LocationService()

    private let ble = BleController()
    private let reader = FrameReader()
    private let geiger = GeigerCounter()
    private let pcap = PcapRecorder()
    private let mqtt = MqttClient()
    private let notifier = NotificationManager()
    private var stationMap: [UInt64: Station] = [:]
    private var replay: ReplayPlayer?
    private var replaySeq: Int64 = 0
    private let maxRecent = 300
    private let d = UserDefaults.standard

    override init() {
        super.init()
        wireBle()
        mqtt.onState = { [weak self] up in
            DispatchQueue.main.async { self?.mqttConnected = up }
        }
        if settings.mqttEnabled { applyMqtt() }
        // Background relaunch (CoreBluetooth state restoration): resume receiving.
        if d.bool(forKey: "wasReceiving") { startBle() }
    }

    var isRecording: Bool { pcap.isRecording }

    // MARK: BLE

    func startBle() {
        stopReplay()
        bleOn = true
        d.set(true, forKey: "wasReceiving")
        if settings.notifyHazards { notifier.requestAuth() }
        location.start(track: settings.trackRecording)
        applyMqtt()
        ble.start()
    }

    func stopBle() {
        bleOn = false
        d.set(false, forKey: "wasReceiving")
        ble.stop()
    }

    func toggleBle() { bleOn ? stopBle() : startBle() }

    func sendConfigToDevice() {
        ble.writeConfig(CycleConfig(discSniffMs: settings.discSniffMs, discBleMs: settings.discBleMs,
                                    connSniffMs: settings.connSniffMs, connBleMs: settings.connBleMs))
    }

    // MARK: Replay

    func startReplay(_ url: URL) {
        stopBle()
        let records = PcapReader.read(url)
        guard !records.isEmpty else { return }
        resetSession()
        replaySeq = 0
        isReplaying = true
        state = .connected
        statusText = "Wiedergabe: \(url.lastPathComponent)"
        let player = ReplayPlayer(records: records, speed: 4.0, onFrame: { [weak self] rec in
            guard let self else { return }
            let frame = self.makeFrame(rec)
            DispatchQueue.main.async { self.ingest([frame]) }
        }, onFinish: { [weak self] in
            DispatchQueue.main.async { self?.stopReplay() }
        })
        replay = player
        player.start()
    }

    func stopReplay() {
        replay?.stop()
        replay = nil
        guard isReplaying else { return }
        isReplaying = false
        state = .idle
        statusText = "Bereit"
    }

    private func makeFrame(_ rec: ReplayPlayer.Rec) -> Frame {
        let d = ItsG5Decoder.decodeFull(rec.payload)
        replaySeq += 1
        return Frame(seq: replaySeq, sec: rec.sec, usec: rec.usec, payload: rec.payload,
                     etherType: d.etherType, msgType: d.msgType, stationId: d.stationId,
                     stationType: d.stationType, latLon: d.latLon, headingDeg: d.headingDeg,
                     speedMps: d.speedMps, spatPhase: d.spatPhase, secured: d.secured, denmCause: d.denmCause)
    }

    // MARK: Recording / misc

    func toggleRecording() {
        if pcap.isRecording {
            pcap.stop()
            recordingURL = pcap.fileURL
        } else {
            recordingURL = pcap.start()
        }
        objectWillChange.send()
    }

    func clearTrack() { location.clearTrack() }

    func resetSession() {
        stationMap.removeAll()
        stations = []
        recentFrames = []
        totalFrames = 0
        typeCounts = [:]
    }

    /// (Re)connects or disconnects the MQTT publisher according to settings.
    func applyMqtt() {
        if settings.mqttEnabled {
            mqtt.start(MqttClient.Config(host: settings.mqttHost, port: UInt16(clamping: settings.mqttPort),
                                         tls: settings.mqttTLS, clientId: settings.mqttClientId, nodeId: settings.nodeId))
        } else {
            mqtt.stop()
        }
    }

    // MARK: ingest

    private func ingest(_ frames: [Frame]) {
        geiger.enabled = settings.geiger
        notifier.enabled = settings.notifyHazards
        totalFrames += frames.count
        let verbose = Diag.shared.verbose
        for f in frames {
            recentFrames.insert(f, at: 0)
            typeCounts[f.msgType, default: 0] += 1
            if verbose { Diag.shared.log("RX " + Self.frameDump(f)) }
            geiger.tick(for: f)
            if f.msgType == .denm {
                notifier.notifyHazard(f)
                if verbose { Diag.shared.log("   → DENM-Notification") }
            }
            if pcap.isRecording { pcap.write(f) }
            if settings.mqttEnabled, !isReplaying, mqtt.isConnected {
                mqtt.publish(f.payload)
                if verbose { Diag.shared.log("   → MQTT publish \(f.len)B") }
            }
            if let sid = f.stationId {
                let isNew = stationMap[sid] == nil
                var s = stationMap[sid] ?? Station(id: sid)
                s.update(with: f)
                stationMap[sid] = s
                if verbose { Diag.shared.log("   → Station \(isNew ? "neu" : "upd") \(sid) (\(stationMap.count) ges.)") }
            }
        }
        if recentFrames.count > maxRecent {
            recentFrames.removeLast(recentFrames.count - maxRecent)
        }
        stations = stationMap.values.sorted { $0.lastSeq > $1.lastSeq }
    }

    private static func hex(_ b: [UInt8]) -> String {
        b.map { String(format: "%02x", $0) }.joined()
    }

    /// Full per-frame decode dump for the verbose debug log.
    private static func frameDump(_ f: Frame) -> String {
        var p = "#\(f.seq) \(f.msgType.short) len=\(f.len)"
        p += " eth=" + (f.etherType.map { String(format: "0x%04x", $0) } ?? "-")
        p += " st=" + (f.stationId.map { String($0) } ?? "-")
        p += " stType=" + (f.stationType.map { String($0) } ?? "-")
        if let g = f.latLon { p += String(format: " @%.6f,%.6f", g.lat, g.lon) } else { p += " @-" }
        p += " hdg=" + (f.headingDeg.map { String(format: "%.1f", $0) } ?? "-")
        p += " spd=" + (f.speedMps.map { String(format: "%.1f", $0) } ?? "-")
        p += " spat=" + (f.spatPhase.map { String(describing: $0) } ?? "-")
        p += " sec=" + (f.secured.map { $0 ? "1" : "0" } ?? "-")
        p += " denm=" + (f.denmCause.map { String(describing: $0) } ?? "-")
        p += " hex=" + f.hexPreview(maxBytes: 2048)
        return p
    }

    private func wireBle() {
        ble.onBytes = { [weak self] bytes in
            guard let self else { return }
            let frames = self.reader.feed(bytes)
            if Diag.shared.verbose {
                Diag.shared.log("BLE chunk \(bytes.count)B → \(frames.count) frame(s): \(Self.hex(bytes))")
            }
            guard !frames.isEmpty else { return }
            DispatchQueue.main.async { self.ingest(frames) }
        }
        ble.onState = { [weak self] st, msg in
            DispatchQueue.main.async {
                guard let self, !self.isReplaying else { return }
                self.state = st
                if let msg { self.statusText = msg }
                else if st == .idle { self.statusText = "Bereit" }
            }
        }
        ble.onConfig = { [weak self] c in
            DispatchQueue.main.async {
                guard let self else { return }
                self.config = c
                // Reflect the device's config as the matching safe preset (this also
                // snaps the editable cycle fields to that preset's BLE-safe values).
                self.settings.cycleProfile = CycleProfile.matching(connSniff: c.connSniffMs)
            }
        }
    }
}
