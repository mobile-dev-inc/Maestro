import FlyingFox
import XCTest
import os

@MainActor
struct SwipeRouteHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {        
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(SwipeRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }

        try await swipePrivateAPI(
            start: requestBody.start,
            end: requestBody.end,
            duration: requestBody.duration)

        return HTTPResponse(statusCode: .ok)
    }

    func swipePrivateAPI(start: CGPoint, end: CGPoint, duration: Double) async throws {

        logger.info("Swiping from \(start.debugDescription) to \(end.debugDescription) with \(duration) duration")

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addSwipeEvent(start: start, end: end, duration: duration)

        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
    }

    private func handleError(message: String) -> Data {
        logger.error("Failed to swipe - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
