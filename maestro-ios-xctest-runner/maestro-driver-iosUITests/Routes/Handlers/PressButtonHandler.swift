import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct PressButtonHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? JSONDecoder().decode(PressButtonRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }

        switch requestBody.button {
        case .home:
            XCUIDevice.shared.press(.home)
        case .lock:
            XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        }

        return HTTPResponse(statusCode: .ok)
    }

    private func handleError(message: String) -> Data {
        logger.error("Failed - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
