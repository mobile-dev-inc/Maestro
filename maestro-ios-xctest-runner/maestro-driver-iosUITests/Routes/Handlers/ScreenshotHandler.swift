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
        let t0 = Date()
        logger.info("[ScreenshotHandler] [Start] compressed=\(compressed)")

        let fullScreenshot = XCUIScreen.main.screenshot()
        let t1 = Date()
        let captureMs = Int(t1.timeIntervalSince(t0) * 1000)
        logger.info("[ScreenshotHandler] XCUIScreen.main.screenshot() took \(captureMs)ms")

        let image = compressed ? fullScreenshot.image.jpegData(compressionQuality: 0.5) : fullScreenshot.pngRepresentation
        let t2 = Date()
        let encodeMs = Int(t2.timeIntervalSince(t1) * 1000)
        logger.info("[ScreenshotHandler] encode (\(compressed ? "jpeg" : "png")) took \(encodeMs)ms bytes=\(image?.count ?? 0)")

        guard let image = image else {
            logger.error("[ScreenshotHandler] image encoding returned nil")
            return AppError(type: .precondition, message: "incorrect request body received for screenshot request").httpResponse
        }

        let totalMs = Int(t2.timeIntervalSince(t0) * 1000)
        logger.info("[ScreenshotHandler] [Done] total=\(totalMs)ms (capture=\(captureMs)ms encode=\(encodeMs)ms)")
        return HTTPResponse(statusCode: .ok, body: image)
    }
}
