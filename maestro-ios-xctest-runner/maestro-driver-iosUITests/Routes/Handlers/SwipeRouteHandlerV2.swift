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
        guard let requestBody = try? await JSONDecoder().decode(SwipeRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "incorrect request body provided for swipe request v2").httpResponse
        }
        
        if (requestBody.duration < 0) {
            return AppError(type: .precondition, message: "swipe duration can not be negative").httpResponse
        }
        
        do {
            try await swipePrivateAPI(requestBody)
            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return Self.classify(error).httpResponse
        }
    }

    /// Maps a swipe failure to an `AppError`: an XCUITest timeout (see `NSError.isXCUITestTimeout`)
    /// becomes `.timeout` (HTTP 408, non-retryable, carrying the real message); any other error keeps
    /// the default 500.
    nonisolated static func classify(_ error: Error) -> AppError {
        let message = "Swipe v2 request failure. Error: \(error.localizedDescription)"
        if (error as NSError).isXCUITestTimeout {
            return AppError(type: .timeout, message: message)
        }
        return AppError(message: message)
    }

    func swipePrivateAPI(_ request: SwipeRequest) async throws {
        let (width, height) = ScreenSizeHelper.physicalScreenSize()
        let startPoint = ScreenSizeHelper.orientationAwarePoint(
            width: width,
            height: height,
            point: request.start
        )
        let endPoint = ScreenSizeHelper.orientationAwarePoint(
            width: width,
            height: height,
            point: request.end
        )
        
        let description = "Swipe (v2) from \(request.start) to \(request.end) with \(request.duration) duration"
        logger.info("\(description)")

        let eventTarget = EventTarget()
        try await eventTarget.dispatchEvent(description: description) {
            EventRecord(orientation: ScreenSizeHelper.currentInterfaceOrientation())
                .addSwipeEvent(
                    start: startPoint,
                    end: endPoint,
                    duration: request.duration
                )
        }
    }
}
