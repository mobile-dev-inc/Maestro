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
        guard let requestBody = try? JSONDecoder().decode(EraseTextRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body for erase text request").httpResponse
        }
        
        do {
            let start = Date()
            
            let appId = RunningApp.getForegroundAppId(requestBody.appIds)
            if let errorResponse = await waitUntilKeyboardIsPresented(appId: appId) {
                return errorResponse
            }

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
    
    private func waitUntilKeyboardIsPresented(appId: String?) async -> HTTPResponse? {
        let isKeyboardPresented: Bool = (try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            guard let appId = appId else { return true }
            return XCUIApplication(bundleIdentifier: appId).keyboards.firstMatch.exists
        }) ?? false

        // Return an error response if the keyboard is not presented
        if !isKeyboardPresented {
            return AppError(type: .timeout, message: "Keyboard not presented within the timeout").httpResponse
        }
        return nil
    }
}
