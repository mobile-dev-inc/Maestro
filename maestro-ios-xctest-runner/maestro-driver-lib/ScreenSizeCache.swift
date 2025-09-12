import UIKit

public final class ScreenSizeCache {

    private static var size: (Float, Float)?
    private static var bundleId: String?
    private static var orientation: UIDeviceOrientation?

    public init() {}

    public static func getIfValid(bundleId: String?, orientation: UIDeviceOrientation?) -> (Float, Float)? {
        guard let s = size,
              bundleId == self.bundleId,
              orientation == self.orientation else { return nil }

        return s
    }

    public static func set(bundleId: String?, orientation: UIDeviceOrientation?, size: (Float, Float)?) {
        ScreenSizeCache.bundleId = bundleId
        ScreenSizeCache.orientation = orientation
        ScreenSizeCache.size = size
    }
}
