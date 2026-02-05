import XCTest
import MaestroDriverLib

final class SystemPermissionHelper {

    private static let buttonFinder = PermissionButtonFinder()

    static func handleSystemPermissionAlertIfNeeded(appHierarchy: AXElement, foregroundApp: XCUIApplication) {
        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              let permissions = try? JSONDecoder().decode([String : PermissionValue].self, from: data),
              let notificationsPermission = permissions.first(where: { $0.key == "notifications" }) else {
            return
        }

        if foregroundApp.bundleID != "com.apple.springboard" {
            NSLog("Foreground app is not springboard skipping auto tapping on permissions")
            return
        }

        NSLog("[Start] Foreground app is springboard attempting to tap on permissions dialog")

        let result = buttonFinder.findButtonToTap(for: notificationsPermission.value, in: appHierarchy)

        switch result {
        case .found(let frame):
            NSLog("Found button at frame: \(frame)")
            tapAtCenter(of: frame, in: foregroundApp)
        case .noButtonsFound:
            NSLog("No buttons found in hierarchy")
        case .noActionRequired:
            NSLog("No action required for permission value")
        }

        NSLog("[Done] Foreground app is springboard attempting to tap on permissions dialog")
    }

    /// Tap at the center of an element's frame
    private static func tapAtCenter(of frame: AXFrame, in app: XCUIApplication) {
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

        Task {
            do {
                try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
            } catch {
                NSLog("Error tapping permission button: \(error)")
            }
        }
    }
}