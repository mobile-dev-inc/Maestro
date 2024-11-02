import Foundation
import FlyingFox
import os
import XCTest

@MainActor
struct SetOrientationHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? JSONDecoder().decode(SetOrientationRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body provided for set orientation").httpResponse
        }

        XCUIDevice.shared.orientation = requestBody.orientation.uiDeviceOrientation
        return HTTPResponse(statusCode: .ok)
    }
}
