import CoreGraphics

/// An app's interface orientation, with `UIInterfaceOrientation`'s raw values. Its own type so it can be
/// unit tested on the host, where UIKit is not available.
public enum InterfaceOrientation: Int {
    case portrait = 1
    case portraitUpsideDown = 2
    case landscapeRight = 3
    case landscapeLeft = 4
}

/// Where a synthesized touch has to go for it to land on a point of the UI.
///
/// The view hierarchy and screenshots are in upright UI space: the screen as the user sees it, whichever
/// way it is turned. A touch sent to a specific display, with a portrait event record, is in that
/// display's portrait space instead. On a foldable the two differ even while the device itself is in
/// portrait — the unfolded inner screen of an iPhone Duo shows its apps in landscape.
public enum TouchSpace {

    public static func touchPoint(_ point: CGPoint, orientation: InterfaceOrientation, portraitSize: CGSize) -> CGPoint {
        switch orientation {
        case .portrait:
            point
        case .portraitUpsideDown:
            CGPoint(x: portraitSize.width - point.x, y: portraitSize.height - point.y)
        case .landscapeRight:
            CGPoint(x: portraitSize.width - point.y, y: point.x)
        case .landscapeLeft:
            CGPoint(x: point.y, y: portraitSize.height - point.x)
        }
    }

    public static func uprightSize(portraitSize: CGSize, orientation: InterfaceOrientation) -> CGSize {
        switch orientation {
        case .portrait, .portraitUpsideDown:
            portraitSize
        case .landscapeLeft, .landscapeRight:
            CGSize(width: portraitSize.height, height: portraitSize.width)
        }
    }
}
