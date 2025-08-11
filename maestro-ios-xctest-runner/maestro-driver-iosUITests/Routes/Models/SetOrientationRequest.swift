import Foundation
import UIKit

struct SetOrientationRequest: Codable {
    let orientation: Orientation

    enum Orientation: String, Codable {
        case portrait
        case landscapeLeft
        case landscapeRight
        case upsideDown

        var uiDeviceOrientation: UIDeviceOrientation {
            switch self {
            case .portrait:
                return .portrait
            case .landscapeLeft:
                return .landscapeLeft
            case .landscapeRight:
                return .landscapeRight
            case .upsideDown:
                return .portraitUpsideDown
            }
        }
    }
}
