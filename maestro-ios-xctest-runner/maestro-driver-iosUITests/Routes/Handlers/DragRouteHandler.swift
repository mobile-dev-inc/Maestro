import FlyingFox
import XCTest
import os
import Foundation

@MainActor
struct DragRouteHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let requestBody: DragRequest
        do {
            requestBody = try await JSONDecoder().decode(DragRequest.self, from: request.bodyData)
        } catch {
            return AppError(
                type: .precondition,
                message: "incorrect request body provided for drag request: \(error)"
            ).httpResponse
        }

        do {
            if let start = requestBody.start, let end = requestBody.end {
                try await dragPrivateAPI(
                    start: start,
                    end: end,
                    duration: requestBody.duration)
            } else {
                return AppError(
                    type: .precondition,
                    message: "Drag request requires start and end coordinates"
                ).httpResponse
            }

            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Drag request failure. Error: \(error.localizedDescription)").httpResponse
        }
    }

    /// Drag using synthesized touch events
    func dragPrivateAPI(start: CGPoint, end: CGPoint, duration: Double) async throws {
        logger.info("Drag from \(start.debugDescription) to \(end.debugDescription) with \(duration) duration")

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addDragEvent(start: start, end: end, duration: duration)

        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)

        // Allow time for iOS to fully process the gesture before accepting new ones
        try await Task.sleep(nanoseconds: 250_000_000) // 250ms
        logger.info("Drag completed")
    }
}
