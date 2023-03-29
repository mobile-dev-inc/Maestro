import FlyingFox
import XCTest
import os

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!,
                            category: String(describing: SwipeRouteHandler.self))

@MainActor
final class SwipeRouteHandler: HTTPHandler {
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {        
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(SwipeRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }

        try await swipePrivateAPI(requestBody)

        return HTTPResponse(statusCode: .ok)
    }

    func swipePrivateAPI(_ request: SwipeRequest) async throws {
        let description = "Swipe from \(request.start) to \(request.end) with \(request.duration) duration"
        logger.info("\(description)")

        let eventTarget = EventTarget(bundleId: request.appId)
        try await eventTarget.dispatchEvent(description: description) {
            EventRecord(orientation: .portrait)
                .addSwipeEvent(
                    start: request.start,
                    end: request.end,
                    duration: request.duration)
        }
    }

    private func handleError(message: String) -> Data {
        logger.error("Failed to swipe - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
