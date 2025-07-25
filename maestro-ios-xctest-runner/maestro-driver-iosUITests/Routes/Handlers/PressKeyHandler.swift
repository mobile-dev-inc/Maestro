import Foundation
import FlyingFox
import os
import XCTest

@MainActor
struct PressKeyHandler: HTTPHandler {
    private let typingFrequency = 30

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? await JSONDecoder().decode(PressKeyRequest.self, from: request.bodyData) else {
            return AppError(type: .precondition, message: "Incorrect request body for press key handler").httpResponse
        }

        do {
            var eventPath = PointerEventPath.pathForTextInput()
            eventPath.type(text: requestBody.xctestKey, typingSpeed: typingFrequency)
            let eventRecord = EventRecord(orientation: .portrait)
            _ = eventRecord.add(eventPath)
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)

            return HTTPResponse(statusCode: .ok)
        } catch let error {
            return AppError(message: "Press key handler failed, error: \(error.localizedDescription)").httpResponse
        }
    }
}
