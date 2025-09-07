import UIKit

public struct ScreenFrame {
    public let width: Float
    public let height: Float

    public init(width: Float, height: Float) {
        self.width = width
        self.height = height
    }
}

public final class ScreenSizePreProcessor {
    
    public static func orientScreenSize(screnFrame: ScreenFrame, orientation: UIDeviceOrientation) -> (Float, Float) {
        let height = screnFrame.height
        let width = screnFrame.width
        
        switch orientation {
        case .landscapeLeft, .landscapeRight:
            // landscape have width >= height
            return (height, width)
            
        case .portrait, .portraitUpsideDown, .faceUp, .faceDown:
            // Portrait mode expects height >= width
            return (width > height) ? (height, width) : (width, height)
        case .unknown:
            // handle same as portrait
            return (width > height) ? (height, width) : (width, height)
        @unknown default:
            return (width, height)
        }
        
    }
}
