import XCTest
import MaestroDriverLib

@MainActor
final class SystemPermissionHelper {

    private static let buttonFinder = PermissionButtonFinder()
    private static let alertSettle = PermissionAlertSettle()
    /// Set while one request waits for the alert, so a concurrent request doesn't tap it too.
    private static var isHandlingAlert = false

    static func handleSystemPermissionAlertIfNeeded(appHierarchy: AXElement, foregroundApp: XCUIApplication) async {
        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              let permissions = try? JSONDecoder().decode([String : PermissionValue].self, from: data),
              let notificationsPermission = permissions.first(where: { $0.key == "notifications" }) else {
            return
        }

        if foregroundApp.bundleID != "com.apple.springboard" {
            NSLog("Foreground app is not springboard skipping auto tapping on permissions")
            return
        }

        guard !isHandlingAlert else { return }
        isHandlingAlert = true
        defer { isHandlingAlert = false }

        NSLog("[Start] Foreground app is springboard attempting to tap on permissions dialog")

        let result = buttonFinder.findButtonToTap(for: notificationsPermission.value, in: appHierarchy)

        switch result {
        case .found(let frame):
            NSLog("Found button at frame: \(frame)")
            let waitStart = Date()
            let settled = await alertSettle.waitUntilScreenIsStatic(
                screenshot: { XCUIScreen.main.screenshot().pngRepresentation },
                sleep: { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
            )
            NSLog("Waited \(Date().timeIntervalSince(waitStart))s for the permission alert to settle (settled: \(settled))")
            if foregroundApp.alerts.firstMatch.exists {
                await tapAtCenter(of: frame, in: foregroundApp)
            } else {
                NSLog("Permission alert went away while waiting, not tapping")
            }
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
