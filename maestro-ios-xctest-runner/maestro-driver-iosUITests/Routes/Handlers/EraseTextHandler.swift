import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct EraseTextHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(EraseTextRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body for erase text request").httpResponse
        }
        
        do {
            let start = Date()
            
            await waitUntilKeyboardIsPresented()

            let deleteText = String(repeating: XCUIKeyboardKey.delete.rawValue, count: requestBody.charactersToErase)
            
            try await TextInputHelper.inputText(deleteText)
            
            let duration = Date().timeIntervalSince(start)
            logger.info("Erase text duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch let error {
            logger.error("Error erasing text of \(requestBody.charactersToErase) characters: \(error)")
            return AppError(message: "Failure in doing erase text, error: \(error.localizedDescription)").httpResponse
        }
    }
    
    private func waitUntilKeyboardIsPresented() async {
        try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
            
            return app.keyboards.firstMatch.exists
        }
    }
}
