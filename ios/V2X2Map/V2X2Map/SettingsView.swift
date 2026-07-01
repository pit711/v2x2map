import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: ReceiverStore
    @EnvironmentObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var showShare = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Karte") {
                    Picker("Kartenlayer", selection: $settings.mapLayer) {
                        ForEach(AppSettings.MapLayer.allCases) { Text($0.title).tag($0) }
                    }
                    Toggle("Auto-Follow (Position folgen)", isOn: $settings.follow)
                    Toggle("Bearing-up (in Fahrtrichtung drehen)", isOn: $settings.bearingUp)
                }

                Section("GPS-Track") {
                    Toggle("Eigene Route aufzeichnen", isOn: $settings.trackRecording)
                        .onChange(of: settings.trackRecording) { _, on in
                            store.location.setTrackRecording(on)
                        }
                    Button("Route löschen", role: .destructive) { store.clearTrack() }
                }

                Section("Hinweise") {
                    Toggle("Geiger-Modus (Klick + Vibration)", isOn: $settings.geiger)
                    Toggle("Gefahren-Push (DENM, auch im Hintergrund)", isOn: $settings.notifyHazards)
                }

                Section("Aufnahmen") {
                    Button {
                        store.toggleRecording()
                    } label: {
                        Label(store.isRecording ? "Aufnahme stoppen" : "Aufnahme starten",
                              systemImage: store.isRecording ? "stop.circle.fill" : "record.circle")
                    }
                    .tint(store.isRecording ? .red : .accentColor)

                    NavigationLink {
                        RecordingsView()
                    } label: {
                        Label("Aufnahmen verwalten & abspielen", systemImage: "externaldrive")
                    }
                }

                Section {
                    Toggle("MQTT senden", isOn: $settings.mqttEnabled)
                        .onChange(of: settings.mqttEnabled) { _, _ in store.applyMqtt() }
                    LabeledContent("Status") {
                        Text(store.mqttConnected ? "verbunden" : "getrennt")
                            .foregroundStyle(store.mqttConnected ? .green : .secondary)
                    }
                    HStack {
                        Text("Broker"); Spacer()
                        TextField("Host", text: $settings.mqttHost)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    HStack {
                        Text("Port"); Spacer()
                        TextField("Port", value: $settings.mqttPort, format: .number.grouping(.never))
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                    }
                    Toggle("TLS", isOn: $settings.mqttTLS)
                    HStack {
                        Text("Node-ID"); Spacer()
                        TextField("Node", text: $settings.nodeId)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("MQTT (OpenTrafficMap)")
                } footer: {
                    Text("Client-ID: \(settings.mqttClientId). Sendet empfangene Frames an its/\(settings.nodeId)/packet.")
                }

                Section {
                    ForEach(CycleProfile.allCases) { p in
                        Button {
                            settings.cycleProfile = p
                        } label: {
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.title).foregroundStyle(.primary)
                                    Text(p.subtitle).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if settings.cycleProfile == p {
                                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                    }
                    Button("An Gerät senden") { store.sendConfigToDevice() }
                        .disabled(!store.bleOn)
                } header: {
                    Text("Empfangsmodus")
                } footer: {
                    Text("Wählt die Abwägung zwischen V2X-Empfang und stabiler Bluetooth-Verbindung. Alle Modi sind auf stabilen Betrieb getestet. Nach der Auswahl „An Gerät senden“ tippen.")
                }

                Section {
                    LabeledContent("Version", value: appVersion)
                    LabeledContent("Transport", value: "Bluetooth LE")
                    NavigationLink {
                        DiagnosticsView()
                    } label: {
                        Label("Diagnose-Log öffnen", systemImage: "stethoscope")
                    }
                    Toggle(isOn: $settings.debugVerbose) {
                        Label("Ausführliches Debug-Log", systemImage: "ladybug")
                    }
                } header: {
                    Text("Diagnose")
                } footer: {
                    Text("Ausführliches Debug-Log protokolliert ALLE empfangenen Pakete inkl. Rohdaten (Hex), Decode-Felder und MQTT/Station-Ereignisse. Nur bei Bedarf einschalten – erzeugt sehr viele Einträge. Log unter „Diagnose-Log öffnen“ ansehen/teilen.\n\nV2X2MAP iOS – empfängt ITS-G5/V2X über den ESP32-C5 per BLE. Läuft im Hintergrund weiter, solange Empfang aktiv ist (außer beim harten Wegwischen der App).")
                }
            }
            .navigationTitle("Einstellungen")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { dismiss() }
                }
            }
            .sheet(isPresented: $showShare) {
                if let url = store.recordingURL { ShareSheet(items: [url]) }
            }
        }
    }

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}
