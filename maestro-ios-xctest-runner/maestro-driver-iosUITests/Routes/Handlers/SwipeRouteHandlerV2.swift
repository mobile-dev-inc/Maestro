import FlyingFox
import XCTest
import os

@MainActor
struct SwipeRouteHandlerV2: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        guard let requestBody = try? JSONDecoder().decode(SwipeRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body provided for swipe request v2").httpResponse
        }
        
        do {
            try await swipePrivateAPI(requestBody)
            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Swipe v2 request failure. Error: \(error.localizedDescription)").httpResponse
        }
    }

    func swipePrivateAPI(_ request: SwipeRequest) async throws {
        let description = "Swipe from \(request.start) to \(request.end) with \(request.duration) duration"
        logger.info("\(description)")

        let runningAppId = RunningApp.getForegroundAppId(request.appIds ?? [])
        let eventTarget = EventTarget(bundleId: runningAppId)
        try await eventTarget.dispatchEvent(description: description) {
            EventRecord(orientation: .portrait)
                .addSwipeEvent(
                    start: request.start,
                    end: request.end,
                    duration: request.duration)
        }
    }
}
