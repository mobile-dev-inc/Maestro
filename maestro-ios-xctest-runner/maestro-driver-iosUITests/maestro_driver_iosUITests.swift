import XCTest
import FlyingFox

class maestro_driver_iosUITests: XCTestCase {
    private static var swizzledOutIdle = false

    override func setUpWithError() throws {
        // XCTest internals sometimes use XCTAssert* instead of exceptions.
        // Setting `continueAfterFailure` so that the xctest runner does not stop
        // when an XCTest internal error happes (eg: when using .allElementsBoundByIndex
        // on a ReactNative app)
        continueAfterFailure = true

        // Disable waiting for quiescence
        if !maestro_driver_iosUITests.swizzledOutIdle { // ensure the swizzle only happens once
            let original = class_getInstanceMethod(objc_getClass("XCUIApplicationProcess") as? AnyClass, Selector(("waitForQuiescenceIncludingAnimationsIdle:")))
            let replaced = class_getInstanceMethod(type(of: self),
                                                   #selector(maestro_driver_iosUITests.replace_waitForQuiescenceIncludingAnimationsIdle))
            
            guard let original = original, let replaced = replaced else { return }
            
            method_exchangeImplementations(original, replaced)
            maestro_driver_iosUITests.swizzledOutIdle = true
        }
    }
    
    @objc func replace_waitForQuiescenceIncludingAnimationsIdle() {
        return
    }
    
    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        try await server.start()
    }
}
