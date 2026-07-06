import XCTest
import MaestroDriverLib

@MainActor
final class SystemPermissionHelper {

    private static let buttonFinder = PermissionButtonFinder()

    static func handleSystemPermissionAlertIfNeeded(appHierarchy: AXElement, foregroundApp: XCUIApplication) async {
        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              let permissions = try? JSONDecoder().decode([String : PermissionValue].self, from: data),
              !permissions.isEmpty else {
            return
        }

        if foregroundApp.bundleID != "com.apple.springboard" {
            NSLog("Foreground app is not springboard skipping auto tapping on permissions")
            return
        }

        guard let dialogKey = buttonFinder.detectPermissionDialog(in: appHierarchy) else {
            NSLog("Foreground app is springboard but no known permission dialog is showing")
            return
        }

        guard let requestedValue = permissions[dialogKey] else {
            NSLog("Permission dialog for \(dialogKey) is showing but the flow did not configure it; leaving it alone")
            return
        }

        NSLog("[Start] Foreground app is springboard attempting to tap on \(dialogKey) permissions dialog")

        let result = buttonFinder.findButtonToTap(forKey: dialogKey, value: requestedValue, in: appHierarchy)

        switch result {
        case .found(let frame):
            NSLog("Found button at frame: \(frame)")
            await tapAtCenter(of: frame, in: foregroundApp)
        case .noButtonsFound:
            NSLog("No buttons found in hierarchy")
        case .noActionRequired:
            NSLog("No action required for permission value")
        @unknown default:
            NSLog("Unknown permission button result: \(result)")
        }

        NSLog("[Done] Foreground app is springboard attempting to tap on permissions dialog")
    }

    /// Tap at the center of an element's frame
    private static func tapAtCenter(of frame: AXFrame, in app: XCUIApplication) async {
        let x = frame.centerX
        let y = frame.centerY

        NSLog("Tapping at coordinates: (\(x), \(y))")

        let (width, height) = ScreenSizeHelper.physicalScreenSize()
        let point = ScreenSizeHelper.orientationAwarePoint(
            width: width,
            height: height,
            point: CGPoint(x: CGFloat(x), y: CGFloat(y))
        )

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addPointerTouchEvent(
            at: point,
            touchUpAfter: nil
        )

        do {
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
        } catch {
            NSLog("Error tapping permission button: \(error)")
        }
    }
}
