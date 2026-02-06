import XCTest
import MaestroDriverLib

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
            return (Float(screenSize.width), Float(screenSize.height))
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

    /// Returns the current UIInterfaceOrientation derived from the device's UIDeviceOrientation.
    ///
    /// Per Apple convention, landscape values are swapped between the two enums:
    /// - UIDeviceOrientation describes the hardware tilt (e.g. `.landscapeLeft` = device rotated left)
    /// - UIInterfaceOrientation describes the UI's compensating rotation (`.landscapeRight` = UI rotated right)
    /// The UI always rotates opposite to the device to keep content upright.
    static func currentInterfaceOrientation() -> UIInterfaceOrientation {
        let orientation = actualOrientation()
        return switch orientation {
        case .landscapeLeft:      .landscapeRight
        case .landscapeRight:     .landscapeLeft
        case .portrait:           .portrait
        case .portraitUpsideDown: .portraitUpsideDown
        default:                  .portrait
        }
    }

    /// Takes device orientation into account.
    static func actualScreenSize() throws -> (Float, Float, UIDeviceOrientation)
    {
        let orientation = actualOrientation()

        let (width, height) = physicalScreenSize()
        let isLandscape = orientation == .landscapeLeft || orientation == .landscapeRight
        let dimsAlreadyMatchOrientation = isLandscape ? (width > height) : (width <= height)

        let (actualWidth, actualHeight) =
            switch orientation {
            case .portrait, .portraitUpsideDown: (width, height)
            case .landscapeLeft, .landscapeRight:
                dimsAlreadyMatchOrientation ? (width, height) : (height, width)
            case .faceDown, .faceUp: (width, height)
            case .unknown:
                throw AppError(
                    message: "Unsupported orientation: \(orientation)")
            @unknown default:
                throw AppError(
                    message: "Unsupported orientation: \(orientation)")
            }

        return (actualWidth, actualHeight, orientation)
    }

    static func orientationAwarePoint(
        width: Float, height: Float, point: CGPoint
    ) -> CGPoint {
        let orientation = actualOrientation()
        let isLandscape = orientation == .landscapeLeft || orientation == .landscapeRight
        let dimsAlreadyMatchOrientation = isLandscape && (width > height)

        // When physicalScreenSize() already returns landscape-correct dims,
        // use the short side as height for the rotation transform.
        let effectiveWidth = dimsAlreadyMatchOrientation ? height : width
        let effectiveHeight = dimsAlreadyMatchOrientation ? width : height

        return switch orientation {
        case .portrait: point
        case .landscapeLeft:
            CGPoint(x: CGFloat(effectiveWidth) - point.y, y: CGFloat(point.x))
        case .landscapeRight:
            CGPoint(x: CGFloat(point.y), y: CGFloat(effectiveHeight) - point.x)
        default: fatalError("Not implemented yet")
        }
    }
}
