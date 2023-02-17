import FlyingFox
import XCTest
import os

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier!,
                            category: String(describing: InputTextRouteHandler.self))

final class InputTextRouteHandler : HTTPHandler {
    private let typingFrequency = 10
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let decoder = JSONDecoder()
        
        guard let requestBody = try? decoder.decode(InputTextRequest.self, from: request.body) else {
            return errorResponse(message: "incorrect request body provided")
        }
        
        do {
            let start = Date()
            try await RunnerDaemonProxy().send(string: requestBody.text, typingFrequency: typingFrequency)
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
}
