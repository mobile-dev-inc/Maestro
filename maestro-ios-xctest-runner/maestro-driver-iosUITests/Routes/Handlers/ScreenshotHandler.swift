import FlyingFox
import XCTest
import os
import maestro_driver_lib

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
            return AppError(type: .precondition, message: "incorrect request body received for screenshot request").httpResponse
        }
        
        let deviceOrientation = XCUIDevice.shared.orientation

        let outputType: ScreenshotPreprocessorOutput = compressed
            ? .jpeg(0.5)
            : .png

        guard let data = DefaultScreenshotPreprocessor().process(
            fullScreenshot.image,
            deviceOrientation: deviceOrientation,
            output: outputType
        ) else {
            return AppError(
                type: .internal,
                message: "image output from screenshot is nil"
            ).httpResponse
        }

        return HTTPResponse(statusCode: .ok, body: data)
    }
    
    private func exif(_ o: UIImage.Orientation) -> Int32 {
        switch o {
        case .up: return 1
        case .down: return 3
        case .left: return 8
        case .right: return 6
        case .upMirrored: return 2
        case .downMirrored: return 4
        case .leftMirrored: return 5
        case .rightMirrored: return 7
        @unknown default: return 1
        }
    }

    func rotatePixels(_ image: UIImage, to expected: UIImage.Orientation) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let ci = CIImage(cgImage: cg)
        let oriented = ci.oriented(forExifOrientation: exif(expected))
        let ctx = CIContext(options: nil)
        guard let out = ctx.createCGImage(oriented, from: oriented.extent) else { return image }
        // Final image is pixel-rotated; set .up so no EXIF flag is needed.
        return UIImage(cgImage: out, scale: image.scale, orientation: .up)
    }
}
