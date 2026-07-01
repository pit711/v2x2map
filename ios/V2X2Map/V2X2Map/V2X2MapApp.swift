import SwiftUI

@main
struct V2X2MapApp: App {
    @StateObject private var store = ReceiverStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(store.settings)
                .environmentObject(store.location)
        }
    }
}
