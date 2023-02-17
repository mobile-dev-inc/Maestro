import FlyingFox
import XCTest
import os

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!,
                            category: String(describing: SwipeRouteHandler.self))

final class SwipeRouteHandler: HTTPHandler {
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let appId = request.query["appId"] else {
            logger.error("Requested view hierarchy for an invalid appId")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest)
        }
        
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(SwipeRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        let response = await MainActor.run {
            let xcuiApplication = XCUIApplication(bundleIdentifier: appId)
            
            let element = xcuiApplication
            
            let velocity: XCUIGestureVelocity
            if let v = requestBody.velocity {
                velocity = XCUIGestureVelocity(CGFloat(v))
            } else {
                velocity = XCUIGestureVelocity.default
            }
            
            let startPoint = element.coordinate(withNormalizedOffset: CGVector(
                    dx: CGFloat(requestBody.startX),
                    dy: CGFloat(requestBody.startY)
            ))
            
            let endPoint = element.coordinate(withNormalizedOffset: CGVector(
                    dx: CGFloat(requestBody.endX),
                    dy: CGFloat(requestBody.endY)
            ))
            
            logger.info("Swiping from \(startPoint) to \(endPoint) with \(velocity.rawValue) velocity")
            
            startPoint.press(
                forDuration: 0.05,
                thenDragTo: endPoint,
                withVelocity: velocity,
                thenHoldForDuration: 0.0
            )
            
            return HTTPResponse(statusCode: .ok)
        }
        
        return response
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to swipe - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
    
}
