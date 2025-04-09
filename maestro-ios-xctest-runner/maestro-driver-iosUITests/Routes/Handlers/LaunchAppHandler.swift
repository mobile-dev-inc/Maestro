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
        guard let requestBody = try? JSONDecoder().decode(LaunchAppRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body provided").httpResponse
        }
        
        do {
            XCUIApplication(bundleIdentifier: requestBody.bundleId).activate()
            
            return HTTPResponse(statusCode: .ok)
        } catch let error {
            logger.error("Error in handleRequest, Error:\(error)");
            return AppError(message: "Failed to launch app. Error: \(error.localizedDescription)").httpResponse
        }
    }
    
}
