import FlyingFox
import XCTest
import os

@MainActor
struct LaunchAppHandler: HTTPHandler {
    
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
 
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(LaunchAppRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }
        
        NSLog("[Start] Launching app with bundle ID: \(requestBody.bundleId)")
        XCUIApplication(bundleIdentifier: requestBody.bundleId).activate()
        NSLog("[Done] Launching app with bundle ID: \(requestBody.bundleId)")

        
        return HTTPResponse(statusCode: .ok)
    }
    
}
