import XCTest

final class SystemPermissionHelper {

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

        // Find buttons in the app hierarchy
        let buttons = findButtons(in: appHierarchy)
        NSLog("Found \(buttons.count) buttons in hierarchy")

        guard buttons.count > 0 else {
            NSLog("No buttons found in hierarchy")
            return
        }

        switch notificationsPermission.value {
        case .allow:
            // Find Allow button - typically has label "Allow"
            if let allowButton = buttons.first(where: { $0.label.lowercased() == "allow" || $0.label.lowercased() == "continue" }) {
                tapAtCenter(of: allowButton.frame, in: foregroundApp)
            } else {
                // Fallback: Allow is typically the second button (index 1)
                tapAtCenter(of: buttons[1].frame, in: foregroundApp)
            }
        case .deny:
            // Find Don't Allow button - typically has label containing "Don't Allow"
            if let denyButton = buttons.first(where: { $0.label.lowercased().contains("don't allow") || $0.label.lowercased() == "cancel" }) {
                tapAtCenter(of: denyButton.frame, in: foregroundApp)
            } else {
                // Fallback: Don't Allow is typically the first button (index 0)
                tapAtCenter(of: buttons[0].frame, in: foregroundApp)
            }
        case .unset, .unknown:
            // do nothing
            break
        }

        NSLog("[Done] Foreground app is springboard attempting to tap on permissions dialog")
    }

    /// Recursively find all button elements in the hierarchy
    private static func findButtons(in element: AXElement) -> [AXElement] {
        var buttons: [AXElement] = []

        // XCUIElement.ElementType.button.rawValue == 9
        if element.elementType == 9 {
            buttons.append(element)
        }

        if let children = element.children {
            for child in children {
                buttons.append(contentsOf: findButtons(in: child))
            }
        }

        return buttons
    }

    /// Tap at the center of an element's frame
    private static func tapAtCenter(of frame: AXFrame, in app: XCUIApplication) {
        let x = (frame["X"] ?? 0) + (frame["Width"] ?? 0) / 2
        let y = (frame["Y"] ?? 0) + (frame["Height"] ?? 0) / 2

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
