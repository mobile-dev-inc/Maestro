import FlyingFox
import XCTest
import os

class InputTextRouteHandler : RouteHandler {
    private enum Constants {
        // 15 characters per second
        static let typingFrequency = 15
        static let maxTextLength = 45
    }
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "InputTextRouteHandler")
    
    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            return errorResponse(message: "incorrect request body provided")
        }
        
        if (requestBody.text.count > Constants.maxTextLength) {
            return fallbackOnCopyPaste(text: requestBody.text, request: request)
        }
        
        do {
            let start = Date()
            try await RunnerDaemonProxy().send(string: requestBody.text, typingFrequency: Constants.typingFrequency)
            let duration = Date().timeIntervalSince(start)
            logger.info("Text input duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            logger.error("Error inputting text '\(requestBody.text)': \(error)")
            return errorResponse(message: "internal error")
        }
    }
    
    private func fallbackOnCopyPaste(text: String, request: FlyingFox.HTTPRequest) -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
        
        let element = xcuiApplication
            .descendants(matching: .any)
            .element(matching: NSPredicate(format: "hasKeyboardFocus == true"))
        
        if (!element.exists) {
            return HTTPResponse(statusCode: .notFound)
        }
        
        element.setText(text: text, application: xcuiApplication)
        
        return HTTPResponse(statusCode: .ok)
    }
    
    private func errorResponse(message: String) -> HTTPResponse {
        logger.error("Failed to input text - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        let errorData = Data(jsonString.utf8)
        return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
    }    
}
