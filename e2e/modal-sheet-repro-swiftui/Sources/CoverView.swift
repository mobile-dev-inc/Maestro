import SwiftUI

struct CoverView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showSheet = false
    @State private var showPassiveSheet = false

    var body: some View {
        VStack(spacing: 24) {
            Text("Full Screen Cover").font(.title)
                .accessibilityIdentifier("cover_title")
            Button("Open nested sheet") { showSheet = true }
                .accessibilityIdentifier("open_nested_sheet")
            Button("Open passive sheet") { showPassiveSheet = true }
                .accessibilityIdentifier("open_passive_sheet")
            Button("Close cover") { dismiss() }
                .accessibilityIdentifier("close_cover")
        }
        .padding()
        .sheet(isPresented: $showSheet) { SheetView() }
        .sheet(isPresented: $showPassiveSheet) { PassiveSheetView() }
    }
}
