import SwiftUI

struct PassiveSheetView: View {
    var body: some View {
        VStack(spacing: 24) {
            Text("Passive sheet text")
                .accessibilityIdentifier("passive_text")
            Button("Disabled action") {}
                .disabled(true)
                .accessibilityIdentifier("disabled_action")
        }
        .padding()
        .presentationDetents([.medium, .large])
    }
}
