import Foundation

/// Result of finding a button to tap for a permission action
public enum PermissionButtonResult: Equatable {
    /// A button was found at the specified frame
    case found(frame: AXFrame)
    /// No buttons found in the hierarchy
    case noButtonsFound
    /// Permission value doesn't require action (unset/unknown/not a permission dialog)
    case noActionRequired
}

/// Pure logic for finding permission dialog buttons in the view hierarchy.
/// This class has no XCUITest dependencies and can be unit tested.
public final class PermissionButtonFinder {

    static let notificationsPermissionLabel = "Would Like to Send You Notifications"

    /// Lowercased label fragments that identify each permission type's Springboard alert,
    /// keyed by the permission names Maestro exposes in flows.
    static let permissionDialogPatterns: [String: [String]] = [
        "notifications": ["would like to send you notifications"],
        "camera": ["would like to access the camera"],
        "microphone": ["would like to access the microphone"],
        "photos": ["would like to access your photo", "would like to add to your photo"],
        "location": ["to use your location"],
        "contacts": ["would like to access your contacts"],
        "calendar": ["access your calendar"],
        "reminders": ["access your reminders"],
        "motion": ["motion & fitness activity", "access your motion"],
        "bluetooth": ["would like to use bluetooth"],
        "userTracking": ["track your activity across"],
        "speech": ["access speech recognition"],
        "medialibrary": ["access apple music", "your media library"],
        "homekit": ["access your home data"],
    ]

    /// Per-permission preferred button labels (lowercased), tried in order before the
    /// generic fallbacks. Alerts like location and tracking use wording of their own.
    static let allowButtonLabels: [String: [String]] = [
        "location": ["allow while using app", "allow once"],
        "photos": ["allow full access", "allow access to all photos"],
        "userTracking": ["allow"],
    ]
    static let defaultAllowLabels = ["allow", "ok", "continue"]

    static let denyButtonLabels: [String: [String]] = [
        "userTracking": ["ask app not to track"],
    ]
    static let defaultDenyLabels = ["don't allow", "deny", "cancel"]

    public init() {}

    /// Recursively finds all button elements in the hierarchy
    /// - Parameter element: The root element to search from
    /// - Returns: An array of all button elements found
    public func findButtons(in element: AXElement) -> [AXElement] {
        var buttons: [AXElement] = []

        if element.elementType == ElementType.button {
            buttons.append(element)
        }

        if let children = element.children {
            for child in children {
                buttons.append(contentsOf: findButtons(in: child))
            }
        }

        return buttons
    }

    /// Determines which permission type's alert (if any) is showing in the hierarchy.
    /// - Parameter element: The root element to search from
    /// - Returns: The Maestro permission key of the matching alert, or `nil`
    public func detectPermissionDialog(in element: AXElement) -> String? {
        var labels: [String] = []
        collectLabels(element, into: &labels)

        for key in Self.permissionDialogPatterns.keys.sorted() {
            guard let patterns = Self.permissionDialogPatterns[key] else { continue }
            for pattern in patterns {
                if labels.contains(where: { $0.contains(pattern) }) {
                    return key
                }
            }
        }
        return nil
    }

    private func collectLabels(_ element: AXElement, into labels: inout [String]) {
        labels.append(element.label.lowercased())
        if let children = element.children {
            for child in children {
                collectLabels(child, into: &labels)
            }
        }
    }

    /// Checks whether the hierarchy contains a notification permission dialog
    /// by searching for the "Would Like to Send You Notifications" label.
    /// - Parameter element: The root element to search from
    /// - Returns: `true` if any element's label contains the notification permission text
    public func isPermissionDialog(_ element: AXElement) -> Bool {
        let label = element.label.lowercased()
        let permissionLabel = Self.notificationsPermissionLabel.lowercased()
        if label.contains(permissionLabel) {
            return true
        }
        if let children = element.children {
            for child in children {
                if isPermissionDialog(child) {
                    return true
                }
            }
        }
        return false
    }

    /// Determines which button should be tapped based on the permission value.
    /// Kept for compatibility: equivalent to `findButtonToTap(forKey: "notifications", ...)`.
    public func findButtonToTap(for permission: PermissionValue, in hierarchy: AXElement) -> PermissionButtonResult {
        return findButtonToTap(forKey: "notifications", value: permission, in: hierarchy)
    }

    /// Determines which button should be tapped for a specific permission type.
    /// - Parameters:
    ///   - key: The Maestro permission key whose alert is expected (see `permissionDialogPatterns`)
    ///   - value: The desired permission action (allow/deny)
    ///   - hierarchy: The view hierarchy to search for buttons
    /// - Returns: The result indicating which button frame to tap, or why no action is needed
    public func findButtonToTap(forKey key: String, value: PermissionValue, in hierarchy: AXElement) -> PermissionButtonResult {
        switch value {
        case .unset, .unknown:
            return .noActionRequired
        case .allow, .deny:
            break
        }

        guard detectPermissionDialog(in: hierarchy) == key else {
            return .noActionRequired
        }

        let buttons = findButtons(in: hierarchy)

        guard !buttons.isEmpty else {
            return .noButtonsFound
        }

        switch value {
        case .allow:
            let preferred = (Self.allowButtonLabels[key] ?? []) + Self.defaultAllowLabels
            if let allowButton = findButton(labeled: preferred, in: buttons) {
                return .found(frame: allowButton.frame)
            }
            // Fallback: Allow is typically the second button (index 1)
            if buttons.count > 1 {
                return .found(frame: buttons[1].frame)
            }
            return .found(frame: buttons[0].frame)

        case .deny:
            let preferred = (Self.denyButtonLabels[key] ?? []) + Self.defaultDenyLabels
            if let denyButton = findButton(labeled: preferred, in: buttons) {
                return .found(frame: denyButton.frame)
            }
            // Fallback: Don't Allow is typically the first button (index 0)
            return .found(frame: buttons[0].frame)

        case .unset, .unknown:
            return .noActionRequired
        }
    }

    /// Finds the first button whose label matches any of the given lowercased labels,
    /// respecting the preference order of `labels`.
    private func findButton(labeled labels: [String], in buttons: [AXElement]) -> AXElement? {
        for wanted in labels {
            if let match = buttons.first(where: { $0.label.lowercased() == wanted }) {
                return match
            }
        }
        // "Don't Allow" renders with a curly apostrophe on some OS versions; fall back to
        // contains-matching for multi-word labels.
        for wanted in labels where wanted.contains(" ") {
            let normalized = wanted.replacingOccurrences(of: "'", with: "\u{2019}")
            if let match = buttons.first(where: {
                let label = $0.label.lowercased()
                return label.contains(wanted) || label.contains(normalized)
            }) {
                return match
            }
        }
        return nil
    }
}
