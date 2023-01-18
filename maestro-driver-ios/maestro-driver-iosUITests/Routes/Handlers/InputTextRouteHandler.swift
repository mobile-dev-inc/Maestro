import FlyingFox
import XCTest
import os

class InputTextRouteHandler : RouteHandler {
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "InputTextRouteHandler")
    
    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        let response = await MainActor.run {
            let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
            
            logger.info("Finding element with keyboard focus")
            
            let element = xcuiApplication
                .descendants(matching: .any)
                .element(matching: NSPredicate(format: "hasKeyboardFocus == true"))
        
            if (!element.exists) {
                return HTTPResponse(statusCode: .notFound)
            }
            
            element.typeText(requestBody.text)
            
            return HTTPResponse(statusCode: .ok)
        }
        
        return response
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to input text - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
    
}
