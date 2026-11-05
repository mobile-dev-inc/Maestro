import SwiftUI

// Fixture for driving a foldable simulator (iPhone Duo, Xcode 27.1+) in every pose and orientation.
// Its controls sit at the corners of the screen and at the far end of a list, where a touch that is
// mapped onto the wrong screen, or rotated the wrong way, lands on something else or on nothing.

@main
struct FoldableFixtureApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @State private var last = "none"
    @State private var text = ""
    @State private var showingList = false

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                VStack(spacing: 12) {
                    Text("Last: \(last)")
                        .font(.title2)
                    Text("Size: \(Int(geometry.size.width))x\(Int(geometry.size.height))")
                    Button("Center") { last = "Center" }
                        .buttonStyle(.borderedProminent)
                    Text("Hold")
                        .padding(12)
                        .background(Color.orange.opacity(0.3), in: Capsule())
                        .onLongPressGesture(minimumDuration: 1) { last = "Hold" }
                    TextField("Type here", text: $text)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .frame(width: 220)
                        .accessibilityIdentifier("input")
                    Text("Typed: \(text)")
                    Button("Show list") { showingList = true }
                }
                VStack {
                    HStack {
                        corner("TopLeft")
                        Spacer()
                        corner("TopRight")
                    }
                    Spacer()
                    HStack {
                        corner("BottomLeft")
                        Spacer()
                        corner("BottomRight")
                    }
                }
                .padding(24)
            }
        }
        // Full screen, not a sheet: in book pose a sheet takes one half, and Maestro scrolls from the
        // middle of the screen, which is then the fold.
        .fullScreenCover(isPresented: $showingList) {
            ListView(last: $last)
        }
    }

    private func corner(_ name: String) -> some View {
        Button(name) { last = name }
            .buttonStyle(.bordered)
    }
}

struct ListView: View {
    @Binding var last: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(1...30, id: \.self) { row in
                Button("Row \(row)") {
                    last = "Row \(row)"
                    dismiss()
                }
            }
            .navigationTitle("Rows")
        }
    }
}
