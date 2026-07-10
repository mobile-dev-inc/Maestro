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
            // An XCUITest UI-query / main-thread-busy timeout is a deterministic, device-answered
            // failure (the screen never reaches idle), not retryable infra. Classify it as .timeout
            // (HTTP 408) so the driver maps it to a non-retryable timeout carrying the real message,
            // instead of a bare 500 the worker relabels "Unknown error" and retries. Mirrors the
            // identical discrimination in ViewHierarchyHandler; any other swipe error keeps the 500.
            if let nsError = error as NSError?,
               nsError.domain == "com.apple.dt.XCTest.XCTFuture",
               nsError.code == 1000,
               nsError.localizedDescription.contains("Timed out while evaluating UI query") {
                return AppError(type: .timeout, message: "Swipe v2 request failure. Error: \(error.localizedDescription)").httpResponse
            } else if let nsError = error as NSError?,
                      nsError.domain == "com.apple.dt.xctest.automation-support.error",
                      nsError.code == 6,
                      nsError.localizedDescription.contains("Unable to perform work on main run loop, process main thread busy for") {
                return AppError(type: .timeout, message: "Swipe v2 request failure. Error: \(nsError.localizedDescription)").httpResponse
            }
            return AppError(message: "Swipe v2 request failure. Error: \(error.localizedDescription)").httpResponse
        }
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
