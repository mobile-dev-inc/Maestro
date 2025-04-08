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
        guard let requestBody = try? await JSONDecoder().decode(PressButtonRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "Incorrect request body for PressButton Handler").httpResponse
        }
        
        switch requestBody.button {
        case .home:
            XCUIDevice.shared.press(.home)
        case .lock:
            XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        }
        return HTTPResponse(statusCode: .ok)
    }
}
