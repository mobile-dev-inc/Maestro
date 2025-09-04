import XCTest

struct ScreenSizeHelper {

    private static var cachedSize: (Float, Float)?
    private static var lastAppBundleId: String?
    private static var lastOrientation: UIDeviceOrientation?

    static func physicalScreenSize() -> (Float, Float) {
        let springboardBundleId = "com.apple.springboard"

        let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: springboardBundleId)

        do {
            let currentAppBundleId = app.bundleID
            let currentOrientation = XCUIDevice.shared.orientation

            if let cached = cachedSize,
                currentAppBundleId == lastAppBundleId,
                currentOrientation == lastOrientation
            {
                NSLog("Returning cached screen size")
                return cached
            }

            let dict = try app.snapshot().dictionaryRepresentation
            let axFrame = AXElement(dict).frame

            // Safely unwrap width/height
            guard let width = axFrame["Width"], let height = axFrame["Height"] else {
                NSLog("Frame keys missing, falling back to SpringBoard.")
                let springboard = XCUIApplication(bundleIdentifier: springboardBundleId)
                let size = springboard.frame.size
                return (Float(size.width), Float(size.height))
            }

            let screenSize = CGSize(width: width, height: height)
            let size = (Float(screenSize.width), Float(screenSize.height))

            // Cache results
            cachedSize = size
            lastAppBundleId = currentAppBundleId
            lastOrientation = currentOrientation

            return size
        } catch let error {
            NSLog("Failure while getting screen size: \(error), falling back to get springboard size.")
            let application = XCUIApplication(
                bundleIdentifier: springboardBundleId)
            let screenSize = application.frame.size
            let orientation = actualOrientation()
            let (actualWidth, actualHeight) =
                switch orientation {
                case .portrait, .portraitUpsideDown: (screenSize.width, screenSize.height)
                case .landscapeLeft, .landscapeRight: (screenSize.height, screenSize.width)
                case .faceDown, .faceUp: (screenSize.width, screenSize.height)
                case .unknown: (screenSize.width, screenSize.height)
                @unknown default: (screenSize.width, screenSize.height)
                }
            return (Float(actualWidth), Float(actualHeight))
        }
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
    static func actualScreenSize() throws -> (Float, Float, UIDeviceOrientation)
    {
        let orientation = actualOrientation()

        let (width, height) = physicalScreenSize()

        return (width, height, orientation)
    }

    static func orientationAwarePoint(
        width: Float, height: Float, point: CGPoint
    ) -> CGPoint {
        let orientation = actualOrientation()

        return switch orientation {
        case .portrait: point
        case .landscapeLeft:
            CGPoint(x: CGFloat(width) - point.y, y: CGFloat(point.x))
        case .landscapeRight:
            CGPoint(x: CGFloat(point.y), y: CGFloat(height) - point.x)
        default: fatalError("Not implemented yet")
        }
    }
}
