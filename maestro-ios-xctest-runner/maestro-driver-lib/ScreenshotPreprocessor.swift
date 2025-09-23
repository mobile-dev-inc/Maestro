import UIKit
import CoreImage

// MARK: - Protocol

public protocol ScreenshotPreprocessor {
    
    /// Processes an image to match the given device orientation.
    ///
    /// - Parameters:
    ///   - image: The captured screenshot.
    ///   - deviceOrientation: Current device/simulator orientation.
    ///   - output: Desired output format (PNG or JPEG with quality).
    ///
    /// - Returns: Encoded image data
    
    func process(_ image: UIImage,
                 deviceOrientation: UIDeviceOrientation,
                 output: ScreenshotPreprocessorOutput) -> Data?
}

// MARK: - Output Format

public enum ScreenshotPreprocessorOutput {
    case png
    case jpeg(_ quality: CGFloat) // 0.0 ... 1.0
}

// MARK: - Implementation

public final class DefaultScreenshotPreprocessor: ScreenshotPreprocessor {
    
    public init() {}
    
    public func process(_ image: UIImage,
                        deviceOrientation: UIDeviceOrientation,
                        output: ScreenshotPreprocessorOutput) -> Data? {
        
        let expected = expectedImageOrientation(for: deviceOrientation)
        
        // If already matches, encode directly
        let normalized: UIImage
        if image.imageOrientation == expected {
            normalized = image // ensure pixels are baked to .up
        } else {
            normalized = rotatePixels(image, to: expected)
        }
        
        switch output {
        case .png:
            return normalized.pngData()
        case .jpeg(let q):
            return normalized.jpegData(compressionQuality: q)
        }
    }
        
    private func expectedImageOrientation(for device: UIDeviceOrientation) -> UIImage.Orientation {
        switch device {
        case .portrait:            return .up
        case .portraitUpsideDown:  return .down
        case .landscapeLeft:       return .left   // home/top at right
        case .landscapeRight:      return .right  // home/top at left
        case .faceUp, .faceDown:   return .up     // fallback
        default:                   return .up
        }
    }
    
    private func rotatePixels(_ image: UIImage, to orientation: UIImage.Orientation) -> UIImage {
        guard let cg = image.cgImage else {
            // Fallback to normalize if CGImage missing
            return image
        }
        
        // Use CI to apply EXIF orientation, then render to real pixels
        let ci = CIImage(cgImage: cg)
        let oriented = ci.oriented(forExifOrientation: exifValue(orientation))
        let ctx = CIContext(options: nil)
        guard let out = ctx.createCGImage(oriented, from: oriented.extent) else {
            return image
        }
        
        // Produce an image whose pixels are already upright
        return UIImage(cgImage: out, scale: image.scale, orientation: .up)
    }
        
    private func exifValue(_ o: UIImage.Orientation) -> Int32 {
        switch o {
        case .up:             return 1
        case .down:           return 3
        case .left:           return 8
        case .right:          return 6
        case .upMirrored:     return 2
        case .downMirrored:   return 4
        case .leftMirrored:   return 5
        case .rightMirrored:  return 7
        @unknown default:     return 1
        }
    }
}
