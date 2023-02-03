import Foundation
import UIKit

class EventRecord {
    let eventRecord: NSObject
    var offset = TimeInterval(0)
    
    init(orientation: UIInterfaceOrientation) {
        let name = "Single-Finger Touch Action"
        // let name = "Multi-Finger Touch Action"
        eventRecord = objc_lookUpClass("XCSynthesizedEventRecord")?
            .alloc()
            .perform(NSSelectorFromString("initWithName:interfaceOrientation:"), with: name, with: orientation)
            .takeUnretainedValue() as! NSObject
    }

    func addPointerTouchEvent(at point: CGPoint, touchUpAfter: TimeInterval = 0.1) {
        let path: NSObject
        do {
            let alloced = objc_lookUpClass("XCPointerEventPath")!.alloc() as! NSObject
            let selector = NSSelectorFromString("initForTouchAtPoint:offset:")
            let imp = alloced.method(for: selector)
            typealias Method = @convention(c) (NSObject, Selector, CGPoint, TimeInterval) -> NSObject
            let method = unsafeBitCast(imp, to: Method.self)
            path = method(alloced, selector, point, offset)
        }

        offset += touchUpAfter

        do {
            let selector = NSSelectorFromString("liftUpAtOffset:")
            let imp = path.method(for: selector)
            typealias Method = @convention(c) (NSObject, Selector, TimeInterval) -> ()
            let method = unsafeBitCast(imp, to: Method.self)
            method(path, selector, offset)
        }

        do {
            let selector = NSSelectorFromString("addPointerEventPath:")
            let imp = eventRecord.method(for: selector)
            typealias Method = @convention(c) (NSObject, Selector, NSObject) -> ()
            let method = unsafeBitCast(imp, to: Method.self)
            method(eventRecord, selector, path)
        }
    }

}
