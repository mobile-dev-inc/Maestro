import FlyingFox
import XCTest
import os

@MainActor
struct ScreenshotHandler: HTTPHandler {
    private let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    func handleRequest(_ request: FlyingFox.HTTPRequest) async throws -> FlyingFox.HTTPResponse {
        let compressed = request.query["compressed"] == "true"
        
        let fullScreenshot = XCUIScreen.main.screenshot()
        let image = compressed ? fullScreenshot.image.jpegData(compressionQuality: 0.5) : fullScreenshot.pngRepresentation
        
        guard let image = image else {
            let errorData = handleError(message: "no image data received from screenshot() operation")
            return HTTPResponse(statusCode: HTTPStatusCode.badRequest, body: errorData)
        }
        
        return HTTPResponse(statusCode: .ok, body: image)
    }
    
    private func handleError(message: String) -> Data {
        logger.error("Failed to capture simulator's screenshot - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
