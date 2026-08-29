import Foundation
import FlyingFox
import os
import XCTest

@MainActor
struct SetAppearanceHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(SetAppearanceRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for set appearance").httpResponse
        }

        guard #available(iOS 15.0, *) else {
            return AppError(type: .precondition, message: "setDarkMode requires iOS 15+").httpResponse
        }

        switch requestBody.appearance.lowercased() {
        case "dark":
            XCUIDevice.shared.appearance = .dark
        case "light":
            XCUIDevice.shared.appearance = .light
        default:
            return AppError(type: .precondition, message: "Invalid appearance value. Use 'dark' or 'light'").httpResponse
        }

        return HTTPResponse(statusCode: .ok)
    }
}
