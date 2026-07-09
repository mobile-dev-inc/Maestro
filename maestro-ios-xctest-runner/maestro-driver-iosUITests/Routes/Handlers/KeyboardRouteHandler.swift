import Foundation
import XCTest
import FlyingFox
import MaestroDriverLib
import os

@MainActor
struct KeyboardRouteHandler: HTTPHandler {
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(KeyboardHandlerRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for input text").httpResponse
        }
        
        do {
            let foregroundApp: XCUIApplication?
            switch ForegroundAppLookupStrategy(appIds: requestBody.appIds) {
            case .discoverOnDevice:
                foregroundApp = RunningApp.getForegroundApp()
            case .constrainedTo(let appIds):
                foregroundApp = XCUIApplication(bundleIdentifier: RunningApp.getForegroundAppId(appIds))
            }
            let isKeyboardVisible = foregroundApp?.keyboards.firstMatch.exists ?? false
            
            let keyboardInfo = KeyboardHandlerResponse(isKeyboardVisible: isKeyboardVisible)
            let responseBody = try JSONEncoder().encode(keyboardInfo)
            return HTTPResponse(statusCode: .ok, body: responseBody)
        } catch let error {
            return AppError(message: "Keyboard handler failed \(error.localizedDescription)").httpResponse
        }
    }
}
