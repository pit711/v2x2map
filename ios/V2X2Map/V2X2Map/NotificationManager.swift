import Foundation
import UserNotifications

/// Fires a local notification (works in the background) when a DENM hazard arrives.
final class NotificationManager {
    var enabled = false
    private var lastFire = Date.distantPast

    func requestAuth() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func notifyHazard(_ frame: Frame) {
        guard enabled else { return }
        let now = Date()
        guard now.timeIntervalSince(lastFire) > 5 else { return }   // throttle bursts
        lastFire = now

        let content = UNMutableNotificationContent()
        content.title = "⚠️ Gefahrenmeldung (DENM)"
        if let c = frame.denmCause {
            content.body = "\(c.label()) \(c.sublabel())".trimmingCharacters(in: .whitespaces)
        } else {
            content.body = "V2X-Gefahrenmeldung in der Nähe empfangen."
        }
        content.sound = .default
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }
}
