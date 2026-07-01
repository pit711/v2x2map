import Foundation

/// Plays back recorded frames preserving their relative timing (scaled by `speed`).
final class ReplayPlayer {
    struct Rec {
        let sec: UInt32
        let usec: UInt32
        let payload: [UInt8]
    }

    private let records: [Rec]
    private let speed: Double
    private let onFrame: (Rec) -> Void
    private let onFinish: () -> Void
    private let queue = DispatchQueue(label: "org.opentrafficmap.replay")
    private var idx = 0
    private var stopped = false

    init(records: [Rec], speed: Double,
         onFrame: @escaping (Rec) -> Void, onFinish: @escaping () -> Void) {
        self.records = records
        self.speed = max(speed, 0.1)
        self.onFrame = onFrame
        self.onFinish = onFinish
    }

    func start() { queue.async { self.step() } }
    func stop() { queue.async { self.stopped = true } }

    private func ts(_ r: Rec) -> Double { Double(r.sec) + Double(r.usec) / 1e6 }

    private func step() {
        guard !stopped, idx < records.count else {
            if !stopped { onFinish() }
            return
        }
        let cur = records[idx]
        onFrame(cur)
        idx += 1
        guard idx < records.count else { onFinish(); return }
        var delta = (ts(records[idx]) - ts(cur)) / speed
        if !delta.isFinite || delta < 0.02 { delta = 0.02 }
        delta = min(delta, 2.0)
        queue.asyncAfter(deadline: .now() + delta) { [weak self] in self?.step() }
    }
}
