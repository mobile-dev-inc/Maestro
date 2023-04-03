import XCTest

final class SystemPermissionHelper {
    static func handleSystemPermissionAlertIfNeeded(springboardApplication: XCUIApplication, appName: String) {
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", appName)

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
            case .unset:
                // do nothing
                break
            }
        }
    }
}
