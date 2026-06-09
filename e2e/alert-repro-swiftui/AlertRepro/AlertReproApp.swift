import SwiftUI

// Minimal SwiftUI app used as the regression fixture for the iOS XCTest
// UI-interruption preflight hang. Presents a real `UIAlertController` via
// SwiftUI's `.alert(...)` modifier — which is the canonical XCUIElementTypeAlert
// in XCTest. Pre-fix, any swipe dispatched against this app produces a
// `Check for interrupting elements affecting Window` entry in the xctest_runner
// log; post-fix, the swizzle in the Maestro runner skips that preflight entirely.

@main
struct AlertReproApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @State private var alertShown = false

    var body: some View {
        VStack(spacing: 24) {
            Text("Alert Repro")
                .font(.title)
            Button("Show Alert") {
                alertShown = true
            }
            .padding()
        }
        .alert(
            "Persistent Alert",
            isPresented: $alertShown,
            actions: {
                Button("Acknowledge", role: .none) {
                    alertShown = false
                }
            },
            message: {
                Text("Real UIAlertController — triggers XCTest's UI-interruption preflight.")
            }
        )
    }
}
