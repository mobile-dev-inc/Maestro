import FlyingFox
import XCTest
import os

@MainActor
struct RunningAppRouteHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    private static let springboardBundleId = "com.apple.springboard"
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(RunningAppRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body for getting running app id request").httpResponse
        }
        
        do {
            let runningAppId = requestBody.appIds.first { appId in
                let app = XCUIApplication(bundleIdentifier: appId)
                
                return app.state == .runningForeground
            }
            
            let response = ["runningAppBundleId": runningAppId ?? RunningAppRouteHandler.springboardBundleId]
            
            let responseData = try JSONSerialization.data(
                withJSONObject: response,
                options: .prettyPrinted
            )
            return HTTPResponse(statusCode: .ok, body: responseData)
        } catch let error {
            return AppError(message: "Failure in getting running app, error: \(error.localizedDescription)").httpResponse
        }
    }
}
