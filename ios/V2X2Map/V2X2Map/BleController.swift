import CoreBluetooth
import Foundation

/// BLE connection state, mirroring the Android `BluetoothController.State`.
enum BleState: String {
    case idle, scanning, connecting, connected, error
}

/// Sniff/BLE duty-cycle config exchanged over the config characteristic (8 bytes, 4× u16 LE).
struct CycleConfig: Equatable {
    var discSniffMs: Int
    var discBleMs: Int
    var connSniffMs: Int
    var connBleMs: Int
}

/// CoreBluetooth counterpart of the Android `BluetoothController`.
///
/// Scans for the ESP32-C5 (`ITS-G5-RX`), subscribes to the notify characteristic
/// (CoreBluetooth writes the CCCD for us via `setNotifyValue`), forwards raw bytes
/// to the caller, and reads/writes the duty-cycle config.
///
/// The C5 duty-cycles its BLE radio (it spends `connSniffMs` sniffing ITS-G5 RF
/// before opening a `connBleMs` BLE window) and uses a short (~5 s) supervision
/// timeout, so the link drops easily. Robustness work mirroring the Android port:
///  - Caches the peripheral reference; reconnects skip the cold scan entirely.
///  - Persists the peripheral UUID and retrieves it on launch (and checks the
///    system's already-connected peripherals), so we reconnect without scanning.
///  - Uses iOS 17 **auto-reconnect** (`CBConnectPeripheralOptionEnableAutoReconnect`)
///    so CoreBluetooth re-establishes a dropped link itself, near-instantly.
///  - A `connect()` to a known peripheral is a *pending* connection that never
///    times out — it completes the moment the C5 advertises again.
///  - Exponential backoff (1 → 2 → 4 → 8 → 30 s) only as a last-resort fallback.
final class BleController: NSObject {
    static let deviceName  = "ITS-G5-RX"
    static let serviceUUID = CBUUID(string: "b6e57e90-12d8-4a47-9b21-3f0000000001")
    static let notifyUUID  = CBUUID(string: "b6e57e90-12d8-4a47-9b21-3f0000000002")
    static let configUUID  = CBUUID(string: "b6e57e90-12d8-4a47-9b21-3f0000000003")

    private static let savedPeripheralKey = "ble.savedPeripheralUUID"

    var onBytes:  (([UInt8]) -> Void)?
    var onState:  ((BleState, String?) -> Void)?
    var onConfig: ((CycleConfig) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var notifyChar: CBCharacteristic?
    private var configChar: CBCharacteristic?

    private var stopped = true
    private var retryCount = 0
    private let queue = DispatchQueue(label: "org.opentrafficmap.ble")

    // ── diagnostics: per-connection-session stats ──────────────────────────
    private var connectStartedAt: Date?     // when the current connect attempt began
    private var connectedAt: Date?          // when the link came up (didConnect)
    private var rxPackets = 0               // notify packets this session
    private var rxBytes = 0                 // notify bytes this session
    private var lastRxLogAt: Date?          // throttle the RX heartbeat
    private var rssiTimer: DispatchSourceTimer?

    /// iOS 17+: let CoreBluetooth transparently re-establish a dropped link.
    private var connectOptions: [String: Any] { [CBConnectPeripheralOptionEnableAutoReconnect: true] }

    private func log(_ m: String) { Diag.shared.log(m) }

    override init() {
        super.init()
        // Restore identifier → iOS can relaunch the app in the background when the
        // receiver reconnects, so reception continues after the app is closed.
        central = CBCentralManager(delegate: self, queue: queue,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "org.opentrafficmap.v2x2map.central"])
    }

    // MARK: public API

    func start() {
        queue.async {
            self.log("start() – central.state=\(self.stateName(self.central.state))")
            self.stopped = false
            self.retryCount = 0
            if self.central.state == .poweredOn { self.beginConnectOrScan() }
            // otherwise centralManagerDidUpdateState will kick it off
        }
    }

    func stop() {
        queue.async {
            self.log("stop() angefordert")
            self.stopped = true
            self.stopRssiPolling()
            if self.central.isScanning { self.central.stopScan() }
            // cancelPeripheralConnection also cancels a pending / auto-reconnect connect.
            if let p = self.peripheral { self.central.cancelPeripheralConnection(p) }
            self.peripheral = nil
            self.notifyChar = nil
            self.configChar = nil
            self.emit(.idle, nil)
        }
    }

    @discardableResult
    func writeConfig(_ c: CycleConfig) -> Bool {
        guard let p = peripheral, let ch = configChar else { return false }
        var buf = [UInt8](repeating: 0, count: 8)
        func pack(_ off: Int, _ v: Int) {
            let cl = min(max(v, 100), 60000)
            buf[off] = UInt8(cl & 0xff)
            buf[off + 1] = UInt8((cl >> 8) & 0xff)
        }
        pack(0, c.discSniffMs); pack(2, c.discBleMs)
        pack(4, c.connSniffMs); pack(6, c.connBleMs)
        p.writeValue(Data(buf), for: ch, type: .withResponse)
        log("Config geschrieben: discSniff=\(c.discSniffMs) discBle=\(c.discBleMs) connSniff=\(c.connSniffMs) connBle=\(c.connBleMs)")
        return true
    }

    // MARK: internals

    /// Reconnect without a cold scan whenever possible: reuse the cached
    /// peripheral, else retrieve it by saved UUID or from the system's already-
    /// connected set, and only fall back to scanning when nothing is known.
    private func beginConnectOrScan() {
        guard !stopped, central.state == .poweredOn else { return }

        // Fast in-session reconnect to the live peripheral object.
        if let p = peripheral {
            log("Reconnect zu gecachter Peripherie \(p.identifier.uuidString.prefix(8))…")
            connect(p); return
        }
        // System may already hold a connection to it (e.g. after background relaunch).
        if let p = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID]).first {
            log("System meldet bereits verbundene Peripherie → übernehme")
            peripheral = p
            p.delegate = self
            if p.state == .connected { p.discoverServices([Self.serviceUUID]) }
            else { connect(p) }
            return
        }
        // Cross-launch: pending-connect to the last-known UUID is fast, BUT it can be
        // STALE (e.g. a firmware reflash changes the advertised address), which would
        // hang forever. So always ALSO scan — whichever resolves first wins, and
        // didDiscover adopts a freshly-found device (dropping a stale pending connect).
        if let uuidStr = UserDefaults.standard.string(forKey: Self.savedPeripheralKey),
           let uuid = UUID(uuidString: uuidStr),
           let p = central.retrievePeripherals(withIdentifiers: [uuid]).first {
            log("Pending-Connect zu gespeicherter UUID \(uuid.uuidString.prefix(8)) + paralleler Scan")
            peripheral = p
            p.delegate = self
            connect(p)
        }
        startScan()
    }

    /// Issue a (pending) connection. iOS never times this out — it resolves the
    /// instant the C5 is reachable again — and `connectOptions` asks CoreBluetooth
    /// to auto-reconnect on later drops.
    private func connect(_ p: CBPeripheral) {
        guard !stopped else { return }
        connectStartedAt = Date()
        log("connect() → \(p.name ?? Self.deviceName) [state=\(peripheralStateName(p.state))]")
        emit(.connecting, "Verbinde mit \(p.name ?? Self.deviceName)…")
        central.connect(p, options: connectOptions)
    }

    private func startScan() {
        guard !stopped else { return }
        log("Scan gestartet (Service \(Self.serviceUUID.uuidString.prefix(8))…)")
        emit(.scanning, "Suche \(Self.deviceName)…")
        // Scanning by service UUID is required for background scanning to work.
        central.scanForPeripherals(withServices: [Self.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    private func emit(_ s: BleState, _ msg: String?) { onState?(s, msg) }

    /// Last-resort fallback (used after a failed connect or scan): retry with
    /// exponential backoff. The common duty-cycle drop is handled by iOS
    /// auto-reconnect / pending connect and never reaches here.
    private func scheduleReconnect() {
        guard !stopped else { return }
        let attempt = retryCount
        retryCount += 1
        let base = min(1 << min(attempt, 4), 30)
        log("Reconnect in \(base)s geplant (Versuch \(attempt + 1))")
        queue.asyncAfter(deadline: .now() + .seconds(base)) { [weak self] in
            guard let self, !self.stopped else { return }
            self.beginConnectOrScan()
        }
    }

    // MARK: RSSI polling (diagnostics)

    /// Read RSSI every few seconds while connected so the log shows whether drops
    /// correlate with a weak signal (range) or happen at full strength (firmware).
    private func startRssiPolling() {
        stopRssiPolling()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 3, repeating: 3)
        t.setEventHandler { [weak self] in
            guard let self, let p = self.peripheral, p.state == .connected else { return }
            p.readRSSI()
        }
        rssiTimer = t
        t.resume()
    }

    private func stopRssiPolling() {
        rssiTimer?.cancel()
        rssiTimer = nil
    }

    // MARK: log helpers

    private func stateName(_ s: CBManagerState) -> String {
        switch s {
        case .poweredOn: return "poweredOn"
        case .poweredOff: return "poweredOff"
        case .resetting: return "resetting"
        case .unauthorized: return "unauthorized"
        case .unsupported: return "unsupported"
        case .unknown: return "unknown"
        @unknown default: return "?"
        }
    }

    private func peripheralStateName(_ s: CBPeripheralState) -> String {
        switch s {
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .disconnecting: return "disconnecting"
        @unknown default: return "?"
        }
    }
}

extension BleController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("central.state → \(stateName(central.state))")
        switch central.state {
        case .poweredOn:    if !stopped { beginConnectOrScan() }
        case .poweredOff:   emit(.error, "Bluetooth ist ausgeschaltet")
        case .unauthorized: emit(.error, "Bluetooth-Berechtigung fehlt")
        case .unsupported:  emit(.error, "Bluetooth wird nicht unterstützt")
        default:            emit(.error, "Bluetooth nicht verfügbar")
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        stopped = false
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let p = peripherals.first {
            log("willRestoreState: Peripherie wiederhergestellt [state=\(peripheralStateName(p.state))]")
            peripheral = p
            p.delegate = self
            if p.state == .connected {
                p.discoverServices([Self.serviceUUID])
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let advName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
        let advServices = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        guard advName == Self.deviceName || advServices.contains(Self.serviceUUID) else { return }

        log("Gefunden: \(advName ?? "?") RSSI=\(RSSI) dBm")
        central.stopScan()
        // If a (possibly stale) pending connect to a different peripheral is in
        // flight, cancel it so we don't leak a connection that never resolves.
        if let old = self.peripheral, old.identifier != peripheral.identifier {
            log("Verwerfe veraltete Peripherie \(old.identifier.uuidString.prefix(8))")
            central.cancelPeripheralConnection(old)
        }
        self.peripheral = peripheral
        peripheral.delegate = self
        // Remember the device so the next reconnect/launch skips the scan.
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.savedPeripheralKey)
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        // Stop the parallel safety-net scan now that we're connected.
        if central.isScanning { central.stopScan() }
        let setup = connectStartedAt.map { String(format: "%.0fms", Date().timeIntervalSince($0) * 1000) } ?? "?"
        log("✓ verbunden (Aufbau \(setup)) – discover services")
        connectedAt = Date()
        rxPackets = 0; rxBytes = 0; lastRxLogAt = nil
        retryCount = 0
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log("✗ connect fehlgeschlagen: \(describeBleError(error))")
        emit(.scanning, "Verbindung fehlgeschlagen – neuer Versuch…")
        scheduleReconnect()
    }

    /// iOS 17 disconnect handler. When `isReconnecting` is true the link dropped
    /// but CoreBluetooth is already re-establishing it (auto-reconnect) — we keep
    /// the peripheral and just inform the UI; service discovery runs again on the
    /// follow-up `didConnect`. When false, we re-issue a pending connect ourselves.
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        let held = connectedAt.map { String(format: "%.1fs", Date().timeIntervalSince($0)) } ?? "?"
        log("⚠︎ getrennt nach \(held) – RX \(rxPackets) Pakete/\(rxBytes) B – Grund: \(describeBleError(error)) – autoReconnect=\(isReconnecting)")
        stopRssiPolling()
        connectedAt = nil
        notifyChar = nil
        configChar = nil
        if stopped {
            self.peripheral = nil
            emit(.idle, nil)
        } else if isReconnecting {
            emit(.connecting, "Verbindung unterbrochen – verbinde neu…")
        } else if let p = self.peripheral {
            emit(.connecting, "Verbindung getrennt – verbinde neu…")
            connect(p)
        } else {
            emit(.scanning, "Verbindung getrennt – neuer Versuch…")
            scheduleReconnect()
        }
    }
}

extension BleController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { log("✗ Service-Discovery: \(describeBleError(error))") }
        guard let svc = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            log("✗ Service \(Self.serviceUUID.uuidString.prefix(8)) nicht gefunden")
            emit(.error, "Service nicht gefunden"); return
        }
        peripheral.discoverCharacteristics([Self.notifyUUID, Self.configUUID], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { log("✗ Characteristic-Discovery: \(describeBleError(error))") }
        var foundNotify = false, foundConfig = false
        for ch in service.characteristics ?? [] {
            switch ch.uuid {
            case Self.notifyUUID:
                notifyChar = ch
                foundNotify = true
                peripheral.setNotifyValue(true, for: ch)
            case Self.configUUID:
                configChar = ch
                foundConfig = true
                peripheral.readValue(for: ch)
            default:
                break
            }
        }
        log("Characteristics gefunden: notify=\(foundNotify) config=\(foundConfig)")
        retryCount = 0
        startRssiPolling()
        emit(.connected, peripheral.name ?? Self.deviceName)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.notifyUUID else { return }
        if let error {
            log("✗ Notify-Subscribe fehlgeschlagen: \(describeBleError(error))")
        } else {
            log("✓ Notify abonniert (isNotifying=\(characteristic.isNotifying))")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == Self.configUUID {
            log(error == nil ? "✓ Config-Write bestätigt" : "✗ Config-Write: \(describeBleError(error))")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard error == nil else { return }
        log("RSSI \(RSSI) dBm")
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { log("✗ didUpdateValue (\(characteristic.uuid.uuidString.prefix(8))): \(describeBleError(error))"); return }
        guard let data = characteristic.value else { return }
        switch characteristic.uuid {
        case Self.notifyUUID:
            rxPackets += 1
            rxBytes += data.count
            // Throttled RX heartbeat so the log shows data is flowing without spam.
            let now = Date()
            if let last = lastRxLogAt, now.timeIntervalSince(last) < 2 {
                // skip
            } else {
                lastRxLogAt = now
                log("RX \(rxPackets) Pakete / \(rxBytes) B (letztes: \(data.count) B)")
            }
            onBytes?([UInt8](data))
        case Self.configUUID where data.count == 8:
            let b = [UInt8](data)
            func u16(_ o: Int) -> Int { Int(b[o]) | (Int(b[o + 1]) << 8) }
            let cfg = CycleConfig(discSniffMs: u16(0), discBleMs: u16(2),
                                  connSniffMs: u16(4), connBleMs: u16(6))
            log("Config gelesen: discSniff=\(cfg.discSniffMs) discBle=\(cfg.discBleMs) connSniff=\(cfg.connSniffMs) connBle=\(cfg.connBleMs)")
            onConfig?(cfg)
        default:
            break
        }
    }
}
