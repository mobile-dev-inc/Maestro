import Foundation
import XCTest
import FlyingFox
import os

@MainActor
struct KeyboardRouteHandler: HTTPHandler {
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(KeyboardHandlerRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for input text").httpResponse
        }
        
        do {
            let foregroundApp: XCUIApplication?
            if requestBody.appIds.isEmpty {
                foregroundApp = RunningApp.getForegroundApp()
            } else {
                foregroundApp = XCUIApplication(bundleIdentifier: RunningApp.getForegroundAppId(requestBody.appIds))
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
