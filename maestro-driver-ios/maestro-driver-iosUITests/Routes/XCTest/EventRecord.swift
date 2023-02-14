import Foundation
import UIKit

struct EventRecord {
    let eventRecord: NSObject
    static let defaultTapDuration = 0.1

    enum Style: String {
        case singeFinger = "Single-Finger Touch Action"
        case multiFinger = "Multi-Finger Touch Action"
    }

    init(orientation: UIInterfaceOrientation, style: Style = .singeFinger) {
        eventRecord = objc_lookUpClass("XCSynthesizedEventRecord")?.alloc()
            .perform(
                NSSelectorFromString("initWithName:interfaceOrientation:"),
                with: style.rawValue,
                with: orientation
            )
            .takeUnretainedValue() as! NSObject
    }

    mutating func addPointerTouchEvent(at point: CGPoint, touchUpAfter: TimeInterval = defaultTapDuration) {
        var path = PointerEventPath.pathForTouch(at: point)
        path.offset += touchUpAfter
        path.liftUp()
        add(path)
    }

    mutating func addSwipeEvent(start: CGPoint, end: CGPoint, duration: TimeInterval) {
        var path = PointerEventPath.pathForTouch(at: start)
        path.offset += Self.defaultTapDuration
        path.moveTo(point: end)
        path.offset += duration
        path.liftUp()
        add(path)
    }

    mutating func add(_ path: PointerEventPath) {
        let selector = NSSelectorFromString("addPointerEventPath:")
        let imp = eventRecord.method(for: selector)
        typealias Method = @convention(c) (NSObject, Selector, NSObject) -> ()
        let method = unsafeBitCast(imp, to: Method.self)
        method(eventRecord, selector, path.path)
    }
}
