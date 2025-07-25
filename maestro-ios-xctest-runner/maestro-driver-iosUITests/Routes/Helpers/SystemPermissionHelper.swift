import XCTest

final class SystemPermissionHelper {
    private static let notificationsPermissionLabel = "Would Like to Send You Notifications"
    
    static func handleSystemPermissionAlertIfNeeded(foregroundApp: XCUIApplication) {
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", notificationsPermissionLabel)

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
        let alert = foregroundApp.alerts.matching(predicate).element
        if alert.exists {
            switch notificationsPermission.value {
            case .allow:
                let allowButton = alert.buttons.element(boundBy: 1)
                if allowButton.exists {
                    allowButton.tap()
                }
            case .deny:
                let dontAllowButton = alert.buttons.element(boundBy: 0)
                if dontAllowButton.exists {
                    dontAllowButton.tap()
                }
            case .unset, .unknown:
                // do nothing
                break
            }
        }
        NSLog("[Done] Foreground app is springboard attempting to tap on permissions dialog")
    }
}
