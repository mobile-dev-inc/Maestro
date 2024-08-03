import XCTest

struct ScreenSizeHelper {
    static func physicalScreenSize() -> (Float, Float) {
        let springboardBundleId = "com.apple.springboard"
        let springboardApp = XCUIApplication(bundleIdentifier: springboardBundleId)
        let screenSize = springboardApp.frame.size
        return (Float(screenSize.width), Float(screenSize.height))
    }
    
    /// Takes device orientation into account.
    static func actualScreenSize() -> (Float, Float) {
        let orientation = XCUIDevice.shared.orientation
        
        let (width, height) = physicalScreenSize()
        let (actualWidth, actualHeight) = switch (orientation) {
        case .portrait, .portraitUpsideDown: (width, height)
        case .landscapeLeft, .landscapeRight: (height, width)
        case .faceDown, .faceUp, .unknown: fatalError("Unsupported orientation: \(orientation)")
        @unknown default: fatalError("Unsupported orientation: \(orientation)")
        }
        
        return (actualWidth, actualHeight)
    }
    
    static func orientationAwarePoint(width: Float, height: Float, point: CGPoint) -> CGPoint {
        let orientation = XCUIDevice.shared.orientation
        
        return switch (orientation) {
        case .portrait: point
        case .landscapeLeft: CGPoint(x: CGFloat(width) - point.y, y: CGFloat(point.x))
        case .landscapeRight: CGPoint(x: CGFloat(point.y), y: CGFloat(height) - point.x)
        default: fatalError("Not implemented yet")
        }
    }
}
