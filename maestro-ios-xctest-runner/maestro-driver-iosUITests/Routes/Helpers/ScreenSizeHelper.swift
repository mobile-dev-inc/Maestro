import XCTest

struct ScreenSizeHelper {
    
    static func physicalScreenSize() -> (Float, Float) {
        let springboardApp = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
        let screenSize = springboardApp.frame.size
        return (Float(screenSize.width), Float(screenSize.height))
    }

    private static func actualOrientation() -> UIDeviceOrientation {
        let orientation = XCUIDevice.shared.orientation
        if orientation == .unknown {
            // If orientation is "unknown", we assume it is "portrait" to
            // work around https://stackoverflow.com/q/78932288/7009800
            return UIDeviceOrientation.portrait
        }

        return orientation
    }

    /// Takes device orientation into account.
    static func actualScreenSize() throws -> (Float, Float, UIDeviceOrientation) {
        let orientation = actualOrientation()

        let (width, height) = physicalScreenSize()
        let (actualWidth, actualHeight) = switch (orientation) {
        case .portrait, .portraitUpsideDown: (width, height)
        case .landscapeLeft, .landscapeRight: (height, width)
        case .faceDown, .faceUp, .unknown: throw AppError(message: "Unsupported orientation: \(orientation)")
        @unknown default: throw AppError(message: "Unsupported orientation: \(orientation)")
        }

        return (actualWidth, actualHeight, orientation)
    }

    static func orientationAwarePoint(width: Float, height: Float, point: CGPoint) -> CGPoint {
        let orientation = actualOrientation()

        return switch (orientation) {
        case .portrait: point
        case .landscapeLeft: CGPoint(x: CGFloat(width) - point.y, y: CGFloat(point.x))
        case .landscapeRight: CGPoint(x: CGFloat(point.y), y: CGFloat(height) - point.x)
        default: fatalError("Not implemented yet")
        }
    }
}
