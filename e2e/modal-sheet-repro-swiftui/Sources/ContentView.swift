import SwiftUI

struct ContentView: View {
    @State private var showCover = false
    @State private var showSheet = false

    var body: some View {
        VStack(spacing: 24) {
            Text("Modal Sheet Reproduction").font(.title)
            Button("Open full screen cover") { showCover = true }
                .accessibilityIdentifier("open_cover")
            Button("Open sheet directly") { showSheet = true }
                .accessibilityIdentifier("open_direct_sheet")
        }
        .padding()
        .fullScreenCover(isPresented: $showCover) { CoverView() }
        .sheet(isPresented: $showSheet) { SheetView() }
    }
}
