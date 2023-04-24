import XCTest
import FlyingFox

class maestro_driver_iosUITests: XCTestCase {
    private static var swizzledOutIdle = false

    override func setUpWithError() throws {
        // Disable waiting for quiescence
        if !maestro_driver_iosUITests.swizzledOutIdle { // ensure the swizzle only happens once
            let original = class_getInstanceMethod(objc_getClass("XCUIApplicationProcess") as? AnyClass, Selector(("waitForQuiescenceIncludingAnimationsIdle:")))
            let replaced = class_getInstanceMethod(type(of: self),
                                                   #selector(maestro_driver_iosUITests.replace_waitForQuiescenceIncludingAnimationsIdle))
            
            guard let original = original, let replaced = replaced else { return }
            
            method_exchangeImplementations(original, replaced)
            maestro_driver_iosUITests.swizzledOutIdle = true
        }

        // In UI tests it is usually best to stop immediately when a failure occurs.
        continueAfterFailure = false

        // In UI tests it’s important to set the initial state - such as interface orientation - required for your tests before they run. The setUp method is a good place to do this.
    }
    
    @objc func replace_waitForQuiescenceIncludingAnimationsIdle() {
        return
    }
    
    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        try await server.start()
    }
}
