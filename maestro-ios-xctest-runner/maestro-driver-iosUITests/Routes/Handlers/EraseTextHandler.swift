import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct EraseTextHandler: HTTPHandler {
    private let typingFrequency = 30

    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let requestBody = try? JSONDecoder().decode(EraseTextRequest.self, from: request.body) else {
            let errorData = handleError(message: "incorrect request body provided")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }

        let deleteText = String(repeating: XCUIKeyboardKey.delete.rawValue, count: requestBody.charactersToErase)
        var eventPath = PointerEventPath.pathForTextInput()
        eventPath.type(text: deleteText, typingSpeed: typingFrequency)
        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.add(eventPath)
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
