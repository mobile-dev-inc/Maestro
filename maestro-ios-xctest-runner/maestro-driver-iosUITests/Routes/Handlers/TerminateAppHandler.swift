import Foundation
import XCTest
import FlyingFox
import os

@MainActor
struct TerminateAppHandler: HTTPHandler {
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(TerminateAppRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for terminating app").httpResponse
        }
        
        NSLog("[Start] Terminating app \(requestBody.appId)")
        XCUIApplication(bundleIdentifier: requestBody.appId).terminate()
        NSLog("[End] Terminating app \(requestBody.appId)")

        return HTTPResponse(statusCode: .ok)
    }
}
