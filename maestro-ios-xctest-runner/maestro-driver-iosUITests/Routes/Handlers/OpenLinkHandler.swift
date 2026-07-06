import Foundation
import XCTest
import FlyingFox
import os

@MainActor
struct OpenLinkHandler: HTTPHandler {

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(OpenLinkRequest.self, from: request.bodyData),
              let url = URL(string: requestBody.link) else {
            return AppError(type: .precondition, message: "incorrect request body provided for open link").httpResponse
        }

        NSLog("[Start] Opening link \(requestBody.link)")
        XCUIDevice.shared.system.open(url)
        NSLog("[End] Opening link \(requestBody.link)")

        return HTTPResponse(statusCode: .ok)
    }
}
