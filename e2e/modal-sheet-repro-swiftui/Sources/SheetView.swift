import SwiftUI

struct SheetView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var count = 0
    @State private var input = ""
    @State private var showAlert = false
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(spacing: 24) {
            Text("Nested Sheet").font(.title)
                .accessibilityIdentifier("sheet_title")
            Text("Tap count: \(count)")
                .accessibilityIdentifier("sheet_count")
            Button("Tap sheet button") { count += 1 }
                .accessibilityIdentifier("sheet_button")
            TextField("Sheet input", text: $input)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 260)
                .accessibilityIdentifier("sheet_input")
                .focused($inputFocused)
                .submitLabel(.done)
                .onSubmit { inputFocused = false }
            Button("Show sheet alert") { showAlert = true }
                .accessibilityIdentifier("show_sheet_alert")
                .alert("Sheet alert", isPresented: $showAlert) {
                    Button("Dismiss alert", role: .cancel) {}
                }
            Button("Close sheet") { dismiss() }
                .accessibilityIdentifier("close_sheet")
        }
        .padding()
        .presentationDetents([.medium, .large])
    }
}
