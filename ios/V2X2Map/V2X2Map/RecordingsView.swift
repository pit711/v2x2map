import SwiftUI

struct RecordingsView: View {
    @EnvironmentObject var store: ReceiverStore
    @Environment(\.dismiss) private var dismiss
    @State private var files: [URL] = []
    @State private var share: ShareItem?

    struct ShareItem: Identifiable { let id = UUID(); let url: URL }

    var body: some View {
        List {
            if files.isEmpty {
                ContentUnavailableView("Keine Aufnahmen", systemImage: "externaldrive",
                    description: Text("Starte eine PCAP-Aufnahme in den Einstellungen, um eine Sitzung aufzuzeichnen."))
            } else {
                ForEach(files, id: \.self) { url in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(url.lastPathComponent).font(.callout)
                            Text(meta(url)).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { store.startReplay(url); dismiss() } label: {
                            Image(systemName: "play.circle.fill").font(.title3)
                        }
                        .buttonStyle(.borderless)
                        Button { share = ShareItem(url: url) } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .onDelete(perform: delete)
            }
        }
        .navigationTitle("Aufnahmen")
        .onAppear(perform: load)
        .sheet(item: $share) { ShareSheet(items: [$0.url]) }
    }

    private func load() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])) ?? []
        files = urls.filter { $0.pathExtension == "pcap" }
            .sorted { (mod($0) ?? .distantPast) > (mod($1) ?? .distantPast) }
    }

    private func delete(_ offsets: IndexSet) {
        for i in offsets { try? FileManager.default.removeItem(at: files[i]) }
        load()
    }

    private func mod(_ url: URL) -> Date? {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
    }

    private func meta(_ url: URL) -> String {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let kb = Double(size) / 1024
        let date = mod(url).map { DateFormatter.localizedString(from: $0, dateStyle: .short, timeStyle: .short) } ?? ""
        return String(format: "%.0f KB · %@", kb, date)
    }
}
