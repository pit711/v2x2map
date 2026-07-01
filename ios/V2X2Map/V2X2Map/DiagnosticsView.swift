import SwiftUI

/// In-app BLE diagnostics log. Newest line on top; share/copy/clear in the toolbar.
struct DiagnosticsView: View {
    @ObservedObject private var diag = Diag.shared
    @State private var showShare = false

    var body: some View {
        Group {
            if diag.lines.isEmpty {
                ContentUnavailableView("Noch keine Log-Einträge",
                                       systemImage: "doc.text.magnifyingglass",
                                       description: Text("Starte den Empfang, dann erscheinen hier die BLE-Ereignisse."))
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(diag.lines.enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.system(.caption2, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
            }
        }
        .navigationTitle("Diagnose-Log")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { showShare = true } label: { Image(systemName: "square.and.arrow.up") }
                    .disabled(diag.lines.isEmpty)
                Button(role: .destructive) { diag.clear() } label: { Image(systemName: "trash") }
                    .disabled(diag.lines.isEmpty)
            }
        }
        .sheet(isPresented: $showShare) {
            ShareSheet(items: [diag.exportText()])
        }
    }
}
