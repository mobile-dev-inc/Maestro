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

    /// Determines which button should be tapped based on the permission value
    /// - Parameters:
    ///   - permission: The desired permission action (allow/deny)
    ///   - hierarchy: The view hierarchy to search for buttons
    /// - Returns: The result indicating which button frame to tap, or why no action is needed
    public func findButtonToTap(for permission: PermissionValue, in hierarchy: AXElement) -> PermissionButtonResult {
        switch permission {
        case .unset, .unknown:
            return .noActionRequired
        case .allow, .deny:
            break
        }

        guard isPermissionDialog(hierarchy) else {
            return .noActionRequired
        }

        let buttons = findButtons(in: hierarchy)

        guard !buttons.isEmpty else {
            return .noButtonsFound
        }

        switch permission {
        case .allow:
            if let allowButton = findAllowButton(in: buttons) {
                return .found(frame: allowButton.frame)
            }
            // Fallback: Allow is typically the second button (index 1)
            if buttons.count > 1 {
                return .found(frame: buttons[1].frame)
            }
            return .found(frame: buttons[0].frame)

        case .deny:
            if let denyButton = findDenyButton(in: buttons) {
                return .found(frame: denyButton.frame)
            }
            // Fallback: Don't Allow is typically the first button (index 0)
            return .found(frame: buttons[0].frame)

        case .unset, .unknown:
            return .noActionRequired
        }
    }

    /// Finds the "Allow" button by label matching
    private func findAllowButton(in buttons: [AXElement]) -> AXElement? {
        buttons.first { button in
            let label = button.label.lowercased()
            return label == "allow" || label == "continue"
        }
    }

    /// Finds the "Don't Allow" / "Deny" button by label matching
    private func findDenyButton(in buttons: [AXElement]) -> AXElement? {
        buttons.first { button in
            let label = button.label.lowercased()
            return label.contains("don't allow") || label == "cancel"
        }
    }
}