import SwiftUI
import CoreLocation

struct ContentView: View {
    @State private var showSettings = false

    var body: some View {
        VStack(spacing: 0) {
            StatusBar(showSettings: $showSettings)
            Divider()
            TabView {
                MapTab()
                    .tabItem { Label("Karte", systemImage: "map") }
                LogTab()
                    .tabItem { Label("Protokoll", systemImage: "list.bullet") }
                StatisticsView()
                    .tabItem { Label("Statistik", systemImage: "chart.bar") }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
    }
}

// MARK: - Status bar

struct StatusBar: View {
    @EnvironmentObject var store: ReceiverStore
    @EnvironmentObject var settings: AppSettings
    @Binding var showSettings: Bool

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(store.state.color).frame(width: 12, height: 12)

            VStack(alignment: .leading, spacing: 1) {
                Text(store.state.title).font(.subheadline.bold())
                Text(store.statusText).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }

            Spacer()

            Text("\(store.totalFrames)")
                .font(.system(.title3, design: .monospaced).bold())
                .contentTransition(.numericText())

            Menu {
                ForEach(MsgType.allCases, id: \.self) { t in
                    Button {
                        settings.toggleType(t)
                    } label: {
                        Label(t.short, systemImage: settings.visibleTypes.contains(t) ? "checkmark.circle.fill" : "circle")
                    }
                }
            } label: {
                Image(systemName: "line.3.horizontal.decrease.circle").font(.title3)
            }

            if store.isReplaying {
                Button("Stop") { store.stopReplay() }
                    .buttonStyle(.borderedProminent).tint(.red)
            } else {
                Button(store.bleOn ? "Stop" : "Start") { store.toggleBle() }
                    .buttonStyle(.borderedProminent)
                    .tint(store.bleOn ? .red : .accentColor)
            }

            Button { showSettings = true } label: {
                Image(systemName: "gearshape").font(.title3)
            }
            .accessibilityIdentifier("settingsButton")
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }
}

// MARK: - Map

struct MapTab: View {
    @State private var selected: Station?

    var body: some View {
        MapView(selected: $selected)
            .ignoresSafeArea(edges: .bottom)
            .sheet(item: $selected) { StationDetailView(station: $0) }
    }
}

// MARK: - Grouped log

struct LogTab: View {
    @EnvironmentObject var store: ReceiverStore
    @EnvironmentObject var settings: AppSettings
    @State private var selected: Station?

    private var filtered: [Station] {
        store.stations.filter { settings.visibleTypes.contains($0.msgType) }
    }

    var body: some View {
        Group {
            if filtered.isEmpty {
                ContentUnavailableView(
                    "Keine Stationen",
                    systemImage: "antenna.radiowaves.left.and.right",
                    description: Text("Tippe Start, um den ESP32-Empfänger zu verbinden und ITS-G5-Nachrichten zu empfangen.")
                )
            } else {
                List(filtered) { st in
                    Button { selected = st } label: { StationRow(station: st) }
                        .buttonStyle(.plain)
                }
                .listStyle(.plain)
            }
        }
        .sheet(item: $selected) { st in
            StationDetailView(station: st)
        }
    }
}

struct StationRow: View {
    @EnvironmentObject var store: ReceiverStore
    let station: Station

    var body: some View {
        HStack(spacing: 10) {
            Text(station.msgType.short)
                .font(.caption2.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 7).padding(.vertical, 3)
                .background(station.msgType.color, in: Capsule())

            VStack(alignment: .leading, spacing: 2) {
                Text(station.macLabel).font(.system(.caption, design: .monospaced))
                HStack(spacing: 6) {
                    Text(station.typeName)
                    if station.secured { Image(systemName: "lock.fill") }
                    if let d = distance { Text(d) }
                }
                .font(.caption2).foregroundStyle(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                if let s = station.speedMps {
                    Text(String(format: "%.0f km/h", s * 3.6)).font(.caption.monospacedDigit())
                }
                Text("×\(station.count)").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var distance: String? {
        guard station.hasPosition, let u = store.location.coordinate else { return nil }
        let d = CLLocation(latitude: u.latitude, longitude: u.longitude)
            .distance(from: CLLocation(latitude: station.lat, longitude: station.lon))
        return d < 1000 ? String(format: "%.0f m", d) : String(format: "%.1f km", d / 1000)
    }
}

#Preview {
    let store = ReceiverStore()
    return ContentView()
        .environmentObject(store)
        .environmentObject(store.settings)
        .environmentObject(store.location)
}
