import Foundation
import UIKit

@objc
final class EventRecord: NSObject {
    let eventRecord: NSObject
    static let defaultTapDuration = 0.1

    enum Style: String {
        case singeFinger = "Single-Finger Touch Action"
        case multiFinger = "Multi-Finger Touch Action"
    }

    private static let displayInitializer = NSSelectorFromString("initWithName:displayID:interfaceOrientation:")

    /// Whether XCTest can send events to a screen other than the main one.
    static var canTargetDisplay: Bool {
        objc_lookUpClass("XCSynthesizedEventRecord")?.instancesRespond(to: displayInitializer) ?? false
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

    /// Events for one screen, by its display id. Their points are in that screen's portrait space,
    /// whichever way its UI is turned; see `TouchSpace`. Only valid when `canTargetDisplay`.
    init(displayID: UInt64, style: Style = .singeFinger) {
        let instance = objc_lookUpClass("XCSynthesizedEventRecord")!.alloc() as! NSObject
        typealias Initializer = @convention(c) (NSObject, Selector, NSString, UInt64, Int) -> NSObject
        let initializer = unsafeBitCast(instance.method(for: Self.displayInitializer), to: Initializer.self)
        eventRecord = initializer(
            instance,
            Self.displayInitializer,
            style.rawValue as NSString,
            displayID,
            UIInterfaceOrientation.portrait.rawValue
        )
    }

    func addPointerTouchEvent(at point: CGPoint, touchUpAfter: TimeInterval?) -> Self {
        var path = PointerEventPath.pathForTouch(at: point)
        path.offset += touchUpAfter ?? Self.defaultTapDuration
        path.liftUp()
        return add(path)
    }

    func addSwipeEvent(start: CGPoint, end: CGPoint, duration: TimeInterval) -> Self {
        var path = PointerEventPath.pathForTouch(at: start)
        path.offset += Self.defaultTapDuration
        path.moveTo(point: end)
        path.offset += duration
        path.liftUp()
        return add(path)
    }

    func add(_ path: PointerEventPath) -> Self {
        let selector = NSSelectorFromString("addPointerEventPath:")
        let imp = eventRecord.method(for: selector)
        typealias Method = @convention(c) (NSObject, Selector, NSObject) -> ()
        let method = unsafeBitCast(imp, to: Method.self)
        method(eventRecord, selector, path.path)
        return self
    }
}
