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
            return errorResponse(message: "incorrect request body provided")
        }
        
        do {
            let start = Date()
            
            let appId = RunningApp.getForegroundAppId(requestBody.appIds)
            await waitUntilKeyboardIsPresented(appId: appId)

            let deleteText = String(repeating: XCUIKeyboardKey.delete.rawValue, count: requestBody.charactersToErase)
            
            try await TextInputHelper.inputText(deleteText)
            
            let duration = Date().timeIntervalSince(start)
            logger.info("Erase text duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            logger.error("Error erasing text of \(requestBody.charactersToErase) characters: \(error)")
            return errorResponse(message: "internal error")
        }

        return HTTPResponse(statusCode: .ok)
    }
    
    private func errorResponse(message: String) -> HTTPResponse {
        logger.error("Failed to erase text - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        let errorData = Data(jsonString.utf8)
        return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
    }
    
    private func waitUntilKeyboardIsPresented(appId: String?) async {
        try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            guard let appId = appId else { return true }

            return XCUIApplication(bundleIdentifier: appId).keyboards.firstMatch.exists
        }
    }
}
