import Foundation
import FlyingFox
import os
import XCTest

@MainActor
struct AppearanceHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard #available(iOS 15.0, *) else {
            return AppError(type: .precondition, message: "setDarkMode requires iOS 15+").httpResponse
        }

        let appearance: String
        switch XCUIDevice.shared.appearance {
        case .dark:
            appearance = "dark"
        default:
            appearance = "light"
        }

        let responseBody = try JSONEncoder().encode(AppearanceResponse(appearance: appearance))
        return HTTPResponse(statusCode: .ok, body: responseBody)
    }
}
