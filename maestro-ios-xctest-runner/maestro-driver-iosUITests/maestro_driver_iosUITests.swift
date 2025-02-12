import XCTest
import FlyingFox
import os

final class maestro_driver_iosUITests: XCTestCase {
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: "maestro_driver_iosUITests"
    )

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

    override class func setUp() {
        logger.trace("setUp")
    }

    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        maestro_driver_iosUITests.logger.info("Will start HTTP server")
        try await server.start()
    }

    override class func tearDown() {
        logger.trace("tearDown")
    }
}
