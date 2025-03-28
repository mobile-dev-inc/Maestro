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
            NSLog("Invalid request for tapping")
            return AppError(type: .precondition, message: "incorrect request body provided for tap route").httpResponse
        }
        
        let (width, height) = ScreenSizeHelper.physicalScreenSize()
        let point = ScreenSizeHelper.orientationAwarePoint(
            width: width,
            height: height,
            point: CGPoint(x: CGFloat(requestBody.x), y: CGFloat(requestBody.y))
        )
        let (x, y) = (point.x, point.y)

        if requestBody.duration != nil {
            NSLog("Long pressing \(x), \(y) for \(requestBody.duration!)s")
        } else {
            NSLog("Tapping \(x), \(y)")
        }

        do {
            let eventRecord = EventRecord(orientation: .portrait)
            _ = eventRecord.addPointerTouchEvent(
                at: CGPoint(x: CGFloat(x), y: CGFloat(y)),
                touchUpAfter: requestBody.duration
            )
            let start = Date()
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
            let duration = Date().timeIntervalSince(start)
            NSLog("Tapping took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            NSLog("Error tapping: \(error)")
            return AppError(message: "Error tapping point: \(error.localizedDescription)").httpResponse
        }
    }
}
