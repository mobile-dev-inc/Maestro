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
            // simctl cannot provide an installed-app candidate list for physical devices.
            // Use on-device discovery only for that empty-list case; simulator sessions keep
            // their constrained candidate lookup so a system overlay cannot win.
            let runningAppId: String
            if requestBody.appIds.isEmpty {
                runningAppId = RunningApp.getForegroundApp()?.bundleID ?? RunningAppRouteHandler.springboardBundleId
            } else {
                runningAppId = RunningApp.getForegroundAppId(requestBody.appIds)
            }
            let response = ["runningAppBundleId": runningAppId]
            
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
