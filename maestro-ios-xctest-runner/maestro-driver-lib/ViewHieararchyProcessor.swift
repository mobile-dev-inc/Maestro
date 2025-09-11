import UIKit

public struct ScreenContext {
    public let deviceOrientation: UIDeviceOrientation
    public let deviceWidth: CGFloat
    public let deviceHeight: CGFloat

    public init(deviceOrientation: UIDeviceOrientation, deviceWidth: CGFloat, deviceHeight: CGFloat) {
        self.deviceOrientation = deviceOrientation
        self.deviceWidth = deviceWidth
        self.deviceHeight = deviceHeight
    }
}

public struct PortraitBounds { public let l, t, r, b: CGFloat }
public struct UIElementBounds { public let x, y, width, height: CGFloat }


public struct ViewHierarchyProcessor {

    /// Returns a copy of `root` with frames oriented for `screen.deviceOrientation
    public static func process(_ root: AXElement, screen: ScreenContext) -> AXElement {
        let respectsDeviceRotation = appRespectsOrientation(root: root, orientation: screen.deviceOrientation)

        let transform = !respectsDeviceRotation
        return mapTree(root, screen: screen, transform: transform)
    }

    // MARK: (2) Decide whether we need to rotate portrait-space frames
    private static func appRespectsOrientation(root: AXElement, orientation: UIDeviceOrientation) -> Bool {
        // AXFrame is [String: Double]
        let w = root.frame["Width"]
        let h = root.frame["Height"]
        // If we can't determine dimensions, be conservative: assume no fix needed.
        guard let width = w, let height = h, width > 0, height > 0 else {
            return true
        }

        switch orientation {
        case .landscapeLeft, .landscapeRight:
            // width always bigger than height in landscape
            return width >= height
        case .portrait:
            // height always biggeer than width in portrait
            return height >= width
        case .portraitUpsideDown:
            // assumption that no app on iphone respects rotation
            return false
        case .faceUp, .faceDown, .unknown:
            return true
        @unknown default:
            return true
        }
    }
    
    private static func orientFrame(_ frame: AXFrame, screen: ScreenContext) -> AXFrame {
        let l = CGFloat(frame["X"] ?? 0)
        let t = CGFloat(frame["Y"] ?? 0)
        let w = CGFloat(frame["Width"] ?? 0)
        let h = CGFloat(frame["Height"] ?? 0)
        let r = l + w
        let b = t + h

        let ob = rotateFromPortrait(
            PortraitBounds(l: l, t: t, r: r, b: b),
            to: screen.deviceOrientation,
            currentWidth: screen.deviceWidth,
            currentHeight: screen.deviceHeight
        )

        return [
            "X": Double(ob.x),
            "Y": Double(ob.y),
            "Width": Double(ob.width),
            "Height": Double(ob.height)
        ]
    }

    private static func mapTree(_ node: AXElement, screen: ScreenContext, transform: Bool) -> AXElement {
        let newFrame = transform ? orientFrame(node.frame, screen: screen) : node.frame
        let newChildren = node.children?.map { mapTree($0, screen: screen, transform: transform) }

        return AXElement(
            identifier: node.identifier,
            frame: newFrame,
            value: node.value,
            title: node.title,
            label: node.label,
            elementType: node.elementType,
            enabled: node.enabled,
            horizontalSizeClass: node.horizontalSizeClass,
            verticalSizeClass: node.verticalSizeClass,
            placeholderValue: node.placeholderValue,
            selected: node.selected,
            hasFocus: node.hasFocus,
            displayID: node.displayID,
            windowContextID: node.windowContextID,
            children: newChildren
        )
    }

    // Rotation math (portrait-space -> current orientation)
    @inline(__always)
    private static func rotateFromPortrait(
        _ p: PortraitBounds,
        to orientation: UIDeviceOrientation,
        currentWidth: CGFloat,
        currentHeight: CGFloat
    ) -> UIElementBounds {
        switch orientation {
        case .landscapeLeft:
            // x = t; y = H - r; w = b - t; h = r - l
            return UIElementBounds(x: p.t, y: currentHeight - p.r, width: p.b - p.t, height: p.r - p.l)
        case .landscapeRight:
            // x = W - b; y = l; w = b - t; h = r - l
            return UIElementBounds(x: currentWidth - p.b, y: p.l, width: p.b - p.t, height: p.r - p.l)
        case .portrait:
            return UIElementBounds(x: p.l, y: p.t, width: p.r - p.l, height: p.b - p.t)
        case .portraitUpsideDown:
            // x = W - r; y = H - b; w = r - l; h = b - t
            return UIElementBounds(x: currentWidth - p.r, y: currentHeight - p.b, width: p.r - p.l, height: p.b - p.t)
        case .faceUp, .faceDown, .unknown:
            return UIElementBounds(x: p.l, y: p.t, width: p.r - p.l, height: p.b - p.t)
        @unknown default:
            return UIElementBounds(x: p.l, y: p.t, width: p.r - p.l, height: p.b - p.t)
        }
    }
}
