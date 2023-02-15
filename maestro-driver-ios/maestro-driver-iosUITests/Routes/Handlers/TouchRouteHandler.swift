import FlyingFox
import XCTest
import os

class TouchRouteHandler : RouteHandler {
    
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "TapRouteHandler")
    
    func handle(request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(TouchRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        logger.info("Tapping \(requestBody.x), \(requestBody.y)")

        let eventRecord = EventRecord(orientation: .portrait)
        eventRecord.addPointerTouchEvent(at: CGPoint(x: CGFloat(requestBody.x), y: CGFloat(requestBody.y)))

        do {
            let start = Date()
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
            let duration = Date().timeIntervalSince(start)
            logger.info("Tapping took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            logger.error("Error tapping: \(error)")
            return HTTPResponse(statusCode: .internalServerError)
        }
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to tap - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
    
}
