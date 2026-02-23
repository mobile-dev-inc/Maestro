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
        guard let requestBody = try? await JSONDecoder().decode(SetOrientationRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for set orientation").httpResponse
        }

        #if !os(tvOS)
        XCUIDevice.shared.orientation = requestBody.orientation.uiDeviceOrientation
        #endif
        
        return HTTPResponse(statusCode: .ok)
    }
}
