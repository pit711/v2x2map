import SwiftUI

struct StatisticsView: View {
    @EnvironmentObject var store: ReceiverStore

    private var sortedCounts: [(type: MsgType, count: Int)] {
        store.typeCounts.sorted { $0.value > $1.value }.map { (type: $0.key, count: $0.value) }
    }
    private var maxCount: Int { max(store.typeCounts.values.max() ?? 1, 1) }

    var body: some View {
        List {
            Section("Sitzung") {
                LabeledContent("Frames gesamt", value: "\(store.totalFrames)")
                LabeledContent("Stationen", value: "\(store.stations.count)")
                LabeledContent("Gesichert", value: "\(store.stations.filter { $0.secured }.count) / \(store.stations.count)")
                LabeledContent("Mit Position", value: "\(store.stations.filter { $0.hasPosition }.count)")
            }

            Section("Nach Nachrichtentyp") {
                if sortedCounts.isEmpty {
                    Text("Noch keine Daten").foregroundStyle(.secondary)
                } else {
                    ForEach(sortedCounts, id: \.type) { row in
                        HStack(spacing: 10) {
                            Text(row.type.short)
                                .font(.caption2.bold()).foregroundStyle(.white)
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .background(row.type.color, in: Capsule())
                            ProgressView(value: Double(row.count), total: Double(maxCount))
                                .tint(row.type.color)
                            Text("\(row.count)").monospacedDigit().frame(width: 64, alignment: .trailing)
                        }
                    }
                }
            }

            Section {
                Button("Statistik zurücksetzen", role: .destructive) { store.resetSession() }
            }
        }
    }
}
