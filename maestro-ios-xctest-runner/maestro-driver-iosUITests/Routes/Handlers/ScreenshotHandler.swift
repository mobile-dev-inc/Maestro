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
        
        let image: Data?
        if let display = ActiveDisplay.current() {
            let screenshot = display.screenshot()
            image = compressed ? screenshot.jpegData(compressionQuality: 0.5) : screenshot.pngData()
        } else {
            let fullScreenshot = XCUIScreen.main.screenshot()
            image = compressed ? fullScreenshot.image.jpegData(compressionQuality: 0.5) : fullScreenshot.pngRepresentation
        }
        
        guard let image = image else {
            return AppError(type: .precondition, message: "incorrect request body received for screenshot request").httpResponse
        }
        
        return HTTPResponse(statusCode: .ok, body: image)
    }
}
