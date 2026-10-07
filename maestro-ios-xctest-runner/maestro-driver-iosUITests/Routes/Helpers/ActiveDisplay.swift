import XCTest
import MaestroDriverLib

/// The screen the foreground app is on, on a device with more than one built-in screen: the cover and
/// inner screens of a foldable such as the iPhone Duo. `current()` is nil on single-screen devices, which
/// keep the runner's original code paths.
///
/// Resolved on every request and never cached, since the device can fold or turn between two commands.
@MainActor
struct ActiveDisplay {
    let displayID: UInt64
    let screen: XCUIScreen
    /// The screen's size in points, held in portrait.
    let portraitSize: CGSize
    /// The app's interface orientation, not the device's: an unfolded iPhone Duo held in portrait shows
    /// its apps in landscape, and `XCUIDevice.orientation` keeps whatever value it was last set to.
    let orientation: InterfaceOrientation

    /// The screen as the user sees it, which is the space of the view hierarchy and of screenshots.
    var uprightSize: CGSize {
        TouchSpace.uprightSize(portraitSize: portraitSize, orientation: orientation)
    }

    func touchPoint(_ point: CGPoint) -> CGPoint {
        TouchSpace.touchPoint(point, orientation: orientation, portraitSize: portraitSize)
    }

    /// The screen as the user sees it. A capture holds the screen's pixels in its native portrait and
    /// only tags the rotation, which the host's image decoder ignores, so the pixels are turned here.
    func screenshot() -> UIImage {
        let image = screen.screenshot().image
        guard image.imageOrientation != .up else {
            return image
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    static func current() -> ActiveDisplay? {
        let screens = XCUIScreen.screens
        guard screens.count > 1, EventRecord.canTargetDisplay else {
            return nil
        }

        let app = RunningApp.getForegroundApp()
            ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
        let appWindows = windows(of: app)
        guard let appDisplay = appDisplayID(app, windows: appWindows, screens: screens),
              let screen = screens.first(where: { displayID(of: $0) == appDisplay }),
              let size = mostCommonSize(appWindows.filter { $0.displayID == appDisplay }.map(\.frame.size))
        else {
            NSLog("Could not tell which screen \(app.bundleID ?? "the foreground app") is on; using the main screen")
            return nil
        }

        let orientation = (number(app, "interfaceOrientation")?.intValue)
            .flatMap(InterfaceOrientation.init(rawValue:)) ?? .portrait
        NSLog("\(app.bundleID ?? "The foreground app") is on display \(appDisplay), \(Int(size.width))x\(Int(size.height)) pt, interface orientation \(orientation.rawValue)")
        return ActiveDisplay(
            displayID: appDisplay,
            screen: screen,
            portraitSize: CGSize(width: min(size.width, size.height), height: max(size.width, size.height)),
            orientation: orientation
        )
    }

    /// An app's windows carry the id of the screen they are on; the application element itself reports 0.
    /// SpringBoard is the exception: it keeps windows on every screen, lit or not, so the screen it is
    /// showing is the one whose capture is not black. A home screen always shows its wallpaper.
    private static func appDisplayID(
        _ app: XCUIApplication,
        windows: [(displayID: UInt64, frame: CGRect)],
        screens: [XCUIScreen]
    ) -> UInt64? {
        let displayIDs = windows.map(\.displayID).reduce(into: [UInt64]()) { ids, id in
            if !ids.contains(id) { ids.append(id) }
        }
        guard app.bundleID == RunningApp.springboardBundleId, displayIDs.count > 1 else {
            return displayIDs.first
        }
        return displayIDs.first { id in
            guard let screen = screens.first(where: { displayID(of: $0) == id }) else { return false }
            return !isBlack(screen.screenshot().image)
        }
    }

    private static func windows(of app: XCUIApplication) -> [(displayID: UInt64, frame: CGRect)] {
        // The windows are the application element's children; nothing below them is needed.
        let previousMaxDepth = AXClientSwizzler.overwriteDefaultParameters["maxDepth"]
        AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = 2
        defer {
            AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = previousMaxDepth
        }

        guard let snapshot = try? app.snapshot() else {
            return []
        }
        return snapshot.children.compactMap { window in
            guard let object = window as? NSObject,
                  let id = number(object, "displayID")?.uint64Value, id != 0 else {
                return nil
            }
            return (displayID: id, frame: window.frame)
        }
    }

    /// SpringBoard's windows on one screen come in more than one size; the screen's own size is the one
    /// most of them share.
    private static func mostCommonSize(_ sizes: [CGSize]) -> CGSize? {
        let counts = sizes.reduce(into: [String: (size: CGSize, count: Int)]()) { counts, size in
            let key = "\(size.width)x\(size.height)"
            counts[key] = (size, (counts[key]?.count ?? 0) + 1)
        }
        return counts.values.max { $0.count < $1.count }?.size
    }

    private static func displayID(of screen: XCUIScreen) -> UInt64? {
        number(screen, "displayID")?.uint64Value
    }

    private static func number(_ object: NSObject, _ key: String) -> NSNumber? {
        guard object.responds(to: NSSelectorFromString(key)) else {
            return nil
        }
        return object.value(forKey: key) as? NSNumber
    }

    /// An unlit screen captures exactly black. Downsampled, since any lit pixel will do.
    private static func isBlack(_ image: UIImage) -> Bool {
        let side = 8
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else {
                return false
            }
            UIGraphicsPushContext(context)
            image.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
            UIGraphicsPopContext()
            return true
        }
        return drawn && !pixels.enumerated().contains { $0.offset % 4 != 3 && $0.element != 0 }
    }
}

/// The screen synthesized events go to, and where a point of the UI lands on it.
@MainActor
struct EventScreen {
    private let display: ActiveDisplay?

    static func current() -> EventScreen {
        EventScreen(display: ActiveDisplay.current())
    }

    func touchPoint(_ point: CGPoint) -> CGPoint {
        if let display {
            return display.touchPoint(point)
        }
        let (width, height) = ScreenSizeHelper.physicalScreenSize()
        return ScreenSizeHelper.orientationAwarePoint(width: width, height: height, point: point)
    }

    func eventRecord(style: EventRecord.Style = .singeFinger) -> EventRecord {
        if let display {
            return EventRecord(displayID: display.displayID, style: style)
        }
        return EventRecord(orientation: ScreenSizeHelper.currentInterfaceOrientation(), style: style)
    }
}
