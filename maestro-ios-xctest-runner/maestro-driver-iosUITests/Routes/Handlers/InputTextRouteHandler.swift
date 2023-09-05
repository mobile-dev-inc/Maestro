import FlyingFox
import XCTest
import os

@MainActor
struct InputTextRouteHandler : HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    private enum Constants {
        static let typingFrequency = 30
        static let slowInputCharactersCount = 1
    }

    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()

        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            return errorResponse(message: "incorrect request body provided")
        }

        do {
            let start = Date()
            
            let appId = RunningApp.getForegroundAppId(requestBody.appIds)
            await waitUntilKeyboardIsPresented(appId: appId)

            // due to different keyboard input listener events (i.e. autocorrection or hardware keyboard connection)
            // characters after the first on are often skipped, so we'll input it with lower typing frequency
            let firstCharacter = String(requestBody.text.prefix(Constants.slowInputCharactersCount))
            logger.info("first character: \(firstCharacter)")
            var eventPath = PointerEventPath.pathForTextInput()
            eventPath.type(text: firstCharacter, typingSpeed: 1)
            let eventRecord = EventRecord(orientation: .portrait)
            _ = eventRecord.add(eventPath)
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)

            // wait 500 ms before dispatching next input text request to avoid iOS dropping characters
            try await Task.sleep(nanoseconds: UInt64(1_000_000_000 * 0.5))
            
            if (requestBody.text.count > Constants.slowInputCharactersCount) {
                let remainingText = String(requestBody.text.suffix(requestBody.text.count - Constants.slowInputCharactersCount))
                logger.info("remaining text: \(remainingText)")
                var eventPath2 = PointerEventPath.pathForTextInput()
                eventPath2.type(text: remainingText, typingSpeed: Constants.typingFrequency)
                let eventRecord2 = EventRecord(orientation: .portrait)
                _ = eventRecord2.add(eventPath2)
                try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord2)
            }

            let duration = Date().timeIntervalSince(start)
            logger.info("Text input duration took \(duration)")
            return HTTPResponse(statusCode: .ok)
        } catch {
            logger.error("Error inputting text '\(requestBody.text)': \(error)")
            return errorResponse(message: "internal error")
        }
    }

    private func errorResponse(message: String) -> HTTPResponse {
        logger.error("Failed to input text - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        let errorData = Data(jsonString.utf8)
        return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
    }
    
    private func waitUntilKeyboardIsPresented(appId: String?) async {
        try? await TimeoutHelper.repeatUntil(timeout: 1, delta: 0.2) {
            guard let appId = appId else { return true }

            return XCUIApplication(bundleIdentifier: appId).keyboards.firstMatch.exists
        }
    }
}
