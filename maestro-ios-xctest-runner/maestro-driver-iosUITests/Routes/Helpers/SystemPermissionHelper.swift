import XCTest

final class SystemPermissionHelper {
    private static let notificationsPermissionLabel = "Would Like to Send You Notifications"
    
    static func handleSystemPermissionAlertIfNeeded(springboardApplication: XCUIApplication) {
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", notificationsPermissionLabel)

        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              let permissions = try? JSONDecoder().decode([String : PermissionValue].self, from: data),
              let notificationsPermission = permissions.first(where: { $0.key == "notifications" }) else {
            return
        }

        let alert = springboardApplication.alerts.matching(predicate).element
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
    }
}
