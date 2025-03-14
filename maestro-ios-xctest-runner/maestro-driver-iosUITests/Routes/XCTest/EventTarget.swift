import Foundation
import XCTest

@MainActor
struct EventTarget {
    
    let eventTarget: NSObject
    
    init() {
        let application = RunningApp.getForegroundApp() ?? XCUIApplication(bundleIdentifier: RunningApp.springboardBundleId)
        
        eventTarget = application.children(matching: .any).firstMatch
            .perform(NSSelectorFromString("eventTarget"))
            .takeUnretainedValue() as! NSObject
    }

    typealias EventBuilder = @convention(block) () -> EventRecord
    func dispatchEvent(description: String, builder: EventBuilder) async throws {
        let selector = NSSelectorFromString("dispatchEventWithDescription:eventBuilder:error:")
        let imp = eventTarget.method(for: selector)

        typealias EventBuilderObjc = @convention(block) () -> NSObject
        typealias Method = @convention(c) (NSObject, Selector, String, EventBuilderObjc, AutoreleasingUnsafeMutablePointer<NSError?>) -> Bool
        var error: NSError?
        let method = unsafeBitCast(imp, to: Method.self)

        _ = method(
            eventTarget,
            selector,
            description,
            { builder().eventRecord },
            &error
        )

        if let error = error {
            throw error
        }
    }
}
