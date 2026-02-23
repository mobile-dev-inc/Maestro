import XCTest
import MaestroDriverLib

// UIKit doesn't include UIDeviceOrientation on tvOS
public enum DeviceOrientation: Int, @unchecked Sendable {
    case unknown = 0
    case portrait = 1 // Device oriented vertically, home button on the bottom
    case portraitUpsideDown = 2 // Device oriented vertically, home button on the top
    case landscapeLeft = 3 // Device oriented horizontally, home button on the right
    case landscapeRight = 4 // Device oriented horizontally, home button on the left
    case faceUp = 5 // Device oriented flat, face up
    case faceDown = 6 // Device oriented flat, face down
}

// UIKit doesn't include UIInterfaceOrientation on tvOS
public enum InterfaceOrientation: Int, @unchecked Sendable {
    case unknown = 0 // Unknown orientation
    case portrait = 1 // Device oriented vertically, home button on the bottom
    case portraitUpsideDown = 2 // Device oriented vertically, home button on the top
    case landscapeLeft = 3 // Device oriented horizontally, home button on the right
    case landscapeRight = 4 // Device oriented horizontally, home button on the left
}

struct ScreenSizeHelper {

    private static var cachedSize: (Float, Float)?
    private static var lastAppBundleId: String?
    private static var lastOrientation: DeviceOrientation?

    static func physicalScreenSize() -> (Float, Float) {
        #if os(tvOS)
        let homescreenBundleId = "com.apple.HeadBoard"
        #else
        let homescreenBundleId = "com.apple.springboard"
        #endif

        let app = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: homescreenBundleId)

        do {
            let currentAppBundleId = app.bundleID
            #if os(tvOS)
            let currentOrientation = Optional(DeviceOrientation.unknown)
            #else
            let currentOrientation = DeviceOrientation(rawValue: XCUIDevice.shared.orientation.rawValue)
            #endif

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
                let homescreen = XCUIApplication(bundleIdentifier: homescreenBundleId)
                let size = homescreen.frame.size
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
            let application = XCUIApplication(bundleIdentifier: homescreenBundleId)
            let screenSize = application.frame.size
            return (Float(screenSize.width), Float(screenSize.height))
        }
    }

    private static func actualOrientation() -> DeviceOrientation {
        #if os(tvOS)
        let orientation = Optional(DeviceOrientation.unknown)
        #else
        let orientation = DeviceOrientation(rawValue: XCUIDevice.shared.orientation.rawValue)
        #endif

        guard let unwrappedOrientation = orientation, orientation != .unknown else {
            // If orientation is "unknown", we assume it is "portrait" to
            // work around https://stackoverflow.com/q/78932288/7009800
            return DeviceOrientation.portrait
        }
        
        return unwrappedOrientation
    }

    /// Returns the current UIInterfaceOrientation derived from the device's UIDeviceOrientation.
    ///
    /// Per Apple convention, landscape values are swapped between the two enums:
    /// - UIDeviceOrientation describes the hardware tilt (e.g. `.landscapeLeft` = device rotated left)
    /// - UIInterfaceOrientation describes the UI's compensating rotation (`.landscapeRight` = UI rotated right)
    /// The UI always rotates opposite to the device to keep content upright.
    static func currentInterfaceOrientation() -> InterfaceOrientation {
        let orientation: DeviceOrientation = actualOrientation()
        return switch orientation {
        case .landscapeLeft:      .landscapeRight
        case .landscapeRight:     .landscapeLeft
        case .portrait:           .portrait
        case .portraitUpsideDown: .portraitUpsideDown
        default:                  .portrait
        }
    }

    /// Takes device orientation into account.
    static func actualScreenSize() throws -> (Float, Float, DeviceOrientation) {
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
