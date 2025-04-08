import FlyingFox
import XCTest
import os

@MainActor
struct InputTextRouteHandler : HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(InputTextRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for input text").httpResponse
        }

        do {
            let start = Date()
            
            await waitUntilKeyboardIsPresented()
            
            try await TextInputHelper.inputText(requestBody.text)

            let duration = Date().timeIntervalSince(start)
            logger.info("Text input duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            return AppError(message: "Error inputting text: \(error.localizedDescription)").httpResponse
        }
    }
    
    private func waitUntilKeyboardIsPresented() async {
        try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)

            return app.keyboards.firstMatch.exists
        }
    }
}
