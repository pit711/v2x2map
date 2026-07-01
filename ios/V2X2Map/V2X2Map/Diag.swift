import Foundation
import os
import CoreBluetooth

/// Lightweight diagnostics log used to chase down BLE connection problems.
///
/// Every line goes to two places:
///  - `os.Logger` (subsystem `com.v2x2map`, category `ble`) so it shows up in
///    **Console.app** on the Mac (select the iPhone, filter subsystem `com.v2x2map`)
///    or via `log stream`.
///  - an in-memory ring buffer that the in-app **Diagnose** view renders, so the
///    log can be read and shared straight from the phone without a cable.
final class Diag: ObservableObject {
    static let shared = Diag()

    private let logger = Logger(subsystem: "com.v2x2map", category: "ble")
    private let lock = NSLock()
    private var buffer: [String] = []
    private let maxLines = 5000
    private var publishScheduled = false

    /// When true, the app emits maximum-verbosity logs (raw BLE chunks, per-frame
    /// decode dumps, MQTT/station events). Toggled from Settings → debug switch.
    var verbose = false

    /// Newest-first list of timestamped lines, published to the UI.
    @Published private(set) var lines: [String] = []

    private let fmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private init() {}

    func log(_ msg: String) {
        logger.log("\(msg, privacy: .public)")
        let line = fmt.string(from: Date()) + "  " + msg
        var schedule = false
        lock.lock()
        buffer.append(line)
        if buffer.count > maxLines { buffer.removeFirst(buffer.count - maxLines) }
        if !publishScheduled { publishScheduled = true; schedule = true }
        lock.unlock()
        // Coalesce UI updates to ~7/s so high-rate verbose logging can't jank the UI.
        if schedule {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                guard let self else { return }
                self.lock.lock()
                self.publishScheduled = false
                let snapshot = Array(self.buffer.reversed())
                self.lock.unlock()
                self.lines = snapshot
            }
        }
    }

    func clear() {
        lock.lock(); buffer.removeAll(); publishScheduled = false; lock.unlock()
        DispatchQueue.main.async { self.lines = [] }
    }

    /// Full chronological text for sharing / copying.
    func exportText() -> String {
        lock.lock(); defer { lock.unlock() }
        return buffer.joined(separator: "\n")
    }
}

/// Human-readable description of a CoreBluetooth error so disconnect reasons are
/// legible in the log (e.g. connection timeout vs. peripheral-initiated close).
func describeBleError(_ error: Error?) -> String {
    guard let error = error as NSError? else { return "kein Fehler (sauber)" }
    let code = error.code
    let known: String
    switch error.domain {
    case CBErrorDomain:
        switch code {
        case 0:  known = "unknown"
        case 1:  known = "invalidParameters"
        case 2:  known = "invalidHandle"
        case 3:  known = "notConnected"
        case 4:  known = "outOfSpace"
        case 5:  known = "operationCancelled"
        case 6:  known = "connectionTimeout (Supervision-Timeout / Link verloren)"
        case 7:  known = "peripheralDisconnected (Gerät hat getrennt)"
        case 8:  known = "uuidNotAllowed"
        case 9:  known = "alreadyAdvertising"
        case 10: known = "connectionFailed"
        case 11: known = "connectionLimitReached"
        case 13: known = "unknownDevice"
        case 14: known = "operationNotSupported"
        default: known = "CBError \(code)"
        }
    default:
        known = error.localizedDescription
    }
    return "[\(error.domain) #\(code)] \(known)"
}
