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

        let orientation: UIDeviceOrientation = {
            switch requestBody.orientation.lowercased() {
            case "landscape": return .landscapeLeft
            case "landscape_left": return .landscapeLeft
            case "landscape_right": return .landscapeRight
            case "portrait": return .portrait
            case "upside_down": return .portraitUpsideDown
            default: return .portrait
            }
        }()

        XCUIDevice.shared.orientation = orientation
        return HTTPResponse(statusCode: .ok)
    }
}
