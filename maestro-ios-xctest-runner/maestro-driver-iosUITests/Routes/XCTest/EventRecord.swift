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

    init(orientation: UIInterfaceOrientation, style: Style = .singeFinger) {
        eventRecord = objc_lookUpClass("XCSynthesizedEventRecord")?.alloc()
            .perform(
                NSSelectorFromString("initWithName:interfaceOrientation:"),
                with: style.rawValue,
                with: orientation
            )
            .takeUnretainedValue() as! NSObject
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

    /// Adds a drag event optimized for Flutter's ReorderableDragStartListener.
    /// Key insight: Start with minimal hold, then very slow initial movement to trigger drag detection.
    func addDragEvent(start: CGPoint, end: CGPoint, duration: TimeInterval, pressDuration: TimeInterval? = nil) -> Self {
        var path = PointerEventPath.pathForTouch(at: start)

        // Very short initial touch (like a tap starting)
        path.offset += 0.05

        // Calculate movement parameters
        let dx = end.x - start.x
        let dy = end.y - start.y

        // Phase 1: Very slow initial movement (10% distance in 40% of time)
        // This should trigger drag recognition without being confused for scroll
        let slowPhaseDistance = 0.1
        let slowPhaseTime = duration * 0.4
        let slowSteps = 5
        for i in 1...slowSteps {
            let t = Double(i) / Double(slowSteps) * slowPhaseDistance
            let x = start.x + dx * CGFloat(t)
            let y = start.y + dy * CGFloat(t)
            path.moveTo(point: CGPoint(x: x, y: y))
            path.offset += slowPhaseTime / TimeInterval(slowSteps)
        }

        // Phase 2: Complete the movement (remaining 90% in 60% of time)
        let fastPhaseTime = duration * 0.6
        let fastSteps = 10
        for i in 1...fastSteps {
            let t = slowPhaseDistance + (1.0 - slowPhaseDistance) * Double(i) / Double(fastSteps)
            let x = start.x + dx * CGFloat(t)
            let y = start.y + dy * CGFloat(t)
            path.moveTo(point: CGPoint(x: x, y: y))
            path.offset += fastPhaseTime / TimeInterval(fastSteps)
        }

        // Brief hold at end before lifting
        path.offset += 0.1

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
