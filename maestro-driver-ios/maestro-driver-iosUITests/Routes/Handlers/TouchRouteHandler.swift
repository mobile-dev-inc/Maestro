import FlyingFox
import XCTest
import os

class TouchRouteHandler : RouteHandler {
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "TapRouteHandler")
    
    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(TouchRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        let response = await MainActor.run {
            let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
            
            logger.info("Tapping \(requestBody.x), \(requestBody.y)")
            
            xcuiApplication
                .coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                .withOffset(CGVector(
                    dx: Double(requestBody.x),
                    dy: Double(requestBody.y)
                ))
                .tap()
            
            return HTTPResponse(statusCode: .ok)
        }
        
        return response
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to tap - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
    
}
