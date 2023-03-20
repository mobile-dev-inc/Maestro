import Foundation
import FlyingFox
import os
import XCTest

@MainActor
struct PressKeyHandler: HTTPHandler {
    private let typingFrequency = 30

    let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? JSONDecoder().decode(PressKeyRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }

        var eventPath = PointerEventPath.pathForTextInput()
        eventPath.type(text: requestBody.xctestKey, typingSpeed: typingFrequency)
        var eventRecord = EventRecord(orientation: .portrait)
        eventRecord.add(eventPath)
        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)

        return HTTPResponse(statusCode: .ok)
    }

    private func handleError(message: String) -> Data {
        logger.error("Failed - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
