import XCTest

/// Launches the app, walks through the tabs and settings, and captures a screenshot at
/// each step (kept as test attachments) so the UI can be visually verified.
final class SmokeUITests: XCTestCase {

    func testWalkthrough() throws {
        let app = XCUIApplication()

        // Auto-allow any system permission dialogs (location, notifications).
        addUIInterruptionMonitor(withDescription: "permissions") { alert in
            for label in ["Beim Verwenden der App erlauben", "Allow While Using App",
                          "Einmal erlauben", "Allow Once", "Erlauben", "Allow", "OK"] {
                let b = alert.buttons[label]
                if b.exists { b.tap(); return true }
            }
            return false
        }

        app.launch()
        sleep(6)            // let OSM map tiles load
        app.tap()           // nudge the interruption monitor if a dialog is up
        sleep(2)
        snapshot("01-Karte")

        let tabs = app.tabBars.buttons
        if tabs.count >= 3 {
            tabs.element(boundBy: 1).tap(); sleep(1); snapshot("02-Protokoll")
            tabs.element(boundBy: 2).tap(); sleep(1); snapshot("03-Statistik")
            tabs.element(boundBy: 0).tap(); sleep(1)
        }

        let gear = app.buttons["settingsButton"]
        if gear.waitForExistence(timeout: 3) {
            gear.tap()
            sleep(1)
            snapshot("04-Einstellungen")
        }
    }

    private func snapshot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }
}
