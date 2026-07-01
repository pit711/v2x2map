import SwiftUI

struct StationDetailView: View {
    let station: Station
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Station") {
                    LabeledContent("Adresse (MAC)", value: station.macLabel)
                    LabeledContent("Typ", value: station.typeName)
                    LabeledContent("Letzter Nachrichtentyp", value: station.msgType.short)
                    if station.hasPosition {
                        LabeledContent("Position",
                                       value: String(format: "%.5f, %.5f", station.lat, station.lon))
                    }
                    if let s = station.speedMps {
                        LabeledContent("Tempo", value: String(format: "%.0f km/h", s * 3.6))
                    }
                    if let h = station.headingDeg {
                        LabeledContent("Richtung", value: String(format: "%.0f°", h))
                    }
                    LabeledContent("Empfangen", value: "\(station.count) Frames")
                    LabeledContent("Gesichert", value: station.secured ? "ja (IEEE 1609.2)" : "nein")
                }

                Section("Letzte Frames") {
                    ForEach(station.recent) { frame in
                        FrameDetailRow(frame: frame)
                    }
                }
            }
            .navigationTitle(station.macLabel)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { dismiss() }
                }
            }
        }
    }
}

struct FrameDetailRow: View {
    let frame: Frame

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(frame.msgType.short)
                    .font(.caption2.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(frame.msgType.color, in: Capsule())
                Text("#\(frame.seq)").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("\(frame.len) B").font(.caption2).foregroundStyle(.secondary)
            }
            if let cause = frame.denmCause {
                Text("Ursache: \(cause.label()) \(cause.sublabel())").font(.caption2)
            }
            if let phase = frame.spatPhase, phase != .unknown {
                Text("Ampelphase: \(phase.rawValue.uppercased())").font(.caption2)
            }
            Text(frame.hexPreview(maxBytes: 32))
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(.vertical, 2)
    }
}
