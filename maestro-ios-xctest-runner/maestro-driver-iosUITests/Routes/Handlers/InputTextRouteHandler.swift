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
        guard let requestBody = try? JSONDecoder().decode(InputTextRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body provided for input text").httpResponse
        }

        do {
            let start = Date()
            
            let appId = RunningApp.getForegroundAppId(requestBody.appIds)
            await waitUntilKeyboardIsPresented(appId: appId)
            
            try await TextInputHelper.inputText(requestBody.text)

            let duration = Date().timeIntervalSince(start)
            logger.info("Text input duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            return AppError(message: "Error inputting text: \(error.localizedDescription)").httpResponse
        }
    }
    
    private func waitUntilKeyboardIsPresented(appId: String?) async {
        try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            guard let appId = appId else { return true }

            return XCUIApplication(bundleIdentifier: appId).keyboards.firstMatch.exists
        }
    }
}
