import FlyingFox
import XCTest
import os

@MainActor
struct TouchRouteHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(TouchRequest.self, from: request.body) else {
            return AppError(type: .precondition, message: "incorrect request body provided for tap route").httpResponse
        }
        
        if requestBody.duration != nil {
            logger.info("Long pressing \(requestBody.x), \(requestBody.y) for \(requestBody.duration!)s")
        } else {
            logger.info("Tapping \(requestBody.x), \(requestBody.y)")
        }

        do {
            let eventRecord = EventRecord(orientation: .portrait)
            _ = eventRecord.addPointerTouchEvent(
                at: CGPoint(x: CGFloat(requestBody.x), y: CGFloat(requestBody.y)),
                touchUpAfter: requestBody.duration
            )
            let start = Date()
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
            let duration = Date().timeIntervalSince(start)
            logger.info("Tapping took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            logger.error("Error tapping: \(error)")
            return AppError(message: "Error tapping point: \(error.localizedDescription)").httpResponse
        }
    }
}
