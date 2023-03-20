import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct ClearTextHandler: HTTPHandler {
    private let typingFrequency = 30

    let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let deleteText = String(repeating: XCUIKeyboardKey.delete.rawValue, count: 40)
        var eventPath = PointerEventPath.pathForTextInput()
        eventPath.type(text: deleteText, typingSpeed: typingFrequency)
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
