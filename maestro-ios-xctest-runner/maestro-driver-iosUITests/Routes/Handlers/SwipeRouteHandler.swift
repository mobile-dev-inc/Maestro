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
        let requestBody: SwipeRequest
        do {
            requestBody = try await JSONDecoder().decode(SwipeRequest.self, from: request.bodyData)
        } catch {
            return AppError(
                type: .precondition,
                message: "incorrect request body provided for swipe request: \(error)"
            ).httpResponse
        }
        

        do {
            try await swipePrivateAPI(
                start: requestBody.start,
                end: requestBody.end,
                duration: requestBody.duration)

            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Swipe request failure. Error: \(error.localizedDescription)").httpResponse
        }
    }

    func swipePrivateAPI(start: CGPoint, end: CGPoint, duration: Double) async throws {
        logger.info("Swipe (v1) from \(start.debugDescription) to \(end.debugDescription) with \(duration) duration")

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addSwipeEvent(start: start, end: end, duration: duration)

        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
    }
}
