import Foundation
import AudioToolbox
import UIKit

/// Audio + haptic "geiger counter": a soft click on each frame, a distinct alert on
/// DENM hazards. Port of the Android `GeigerCounter`.
final class GeigerCounter {
    var enabled = false

    private let impact = UIImpactFeedbackGenerator(style: .light)
    private let warn = UINotificationFeedbackGenerator()
    private var lastClick = Date.distantPast

    func tick(for frame: Frame) {
        guard enabled else { return }
        if frame.msgType == .denm {
            AudioServicesPlaySystemSound(1005)        // distinct alert tone
            warn.notificationOccurred(.warning)
            lastClick = Date()
            return
        }
        // Throttle clicks so a high frame rate doesn't overload audio/haptics.
        let now = Date()
        guard now.timeIntervalSince(lastClick) > 0.08 else { return }
        lastClick = now
        AudioServicesPlaySystemSound(1104)            // "Tock"
        impact.impactOccurred(intensity: 0.6)
    }
}
