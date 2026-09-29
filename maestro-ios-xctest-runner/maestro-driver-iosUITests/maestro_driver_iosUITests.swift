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

/// Pure-logic tests for `SwipeRouteHandlerV2.classify`. No app is launched, so these run on the
/// simulator's test runtime without touching a device. The XCUITest error inputs below are the
/// real domain/code/message observed in production for the DoorDash swipeV2 failures.
final class SwipeRouteHandlerV2ClassificationTests: XCTestCase {

    // Error Domain=com.apple.dt.XCTest.XCTFuture Code=1000
    // "Swipe (v2) from (201.0, 437.0) to (201.0, 786.0) with 0.4 duration: Timed out while evaluating UI query."
    func testUIQueryTimeoutIsClassifiedAsTimeout() {
        let error = NSError(
            domain: "com.apple.dt.XCTest.XCTFuture",
            code: 1000,
            userInfo: [NSLocalizedDescriptionKey:
                "Swipe (v2) from (201.0, 437.0) to (201.0, 786.0) with 0.4 duration: Timed out while evaluating UI query."]
        )
        let result = SwipeRouteHandlerV2.classify(error)
        XCTAssertEqual(result.type, .timeout)
        // Pin the end-to-end contract: .timeout must surface as HTTP 408 (non-retryable), not 500.
        XCTAssertEqual(result.httpResponse.statusCode, .requestTimeout)
    }

    func testMainThreadBusyIsClassifiedAsTimeout() {
        let error = NSError(
            domain: "com.apple.dt.xctest.automation-support.error",
            code: 6,
            userInfo: [NSLocalizedDescriptionKey:
                "Unable to perform work on main run loop, process main thread busy for 5.0s"]
        )
        XCTAssertEqual(SwipeRouteHandlerV2.classify(error).type, .timeout)
    }

    // The same message on a different domain must NOT be reclassified: we only want the specific
    // XCUITest timeouts, not any error that happens to contain the phrase.
    func testTimeoutMessageWithWrongDomainStaysInternal() {
        let error = NSError(
            domain: "com.example.other",
            code: 1000,
            userInfo: [NSLocalizedDescriptionKey: "Timed out while evaluating UI query."]
        )
        XCTAssertEqual(SwipeRouteHandlerV2.classify(error).type, .internal)
    }

    // Right domain, wrong code/message (e.g. a genuine swipe failure) keeps the default 500.
    func testUnrelatedXCTFutureErrorStaysInternal() {
        let error = NSError(
            domain: "com.apple.dt.XCTest.XCTFuture",
            code: 42,
            userInfo: [NSLocalizedDescriptionKey: "Some other failure"]
        )
        let result = SwipeRouteHandlerV2.classify(error)
        XCTAssertEqual(result.type, .internal)
        // Pin the other side of the contract: a non-timeout swipe failure stays HTTP 500.
        XCTAssertEqual(result.httpResponse.statusCode, .internalServerError)
    }

    // Right domain AND code, but a message that lacks the timeout phrase must NOT be reclassified.
    // This isolates the message-substring guard: without it, every XCTFuture/1000 error (including
    // genuine swipe failures) would be misclassified as a non-retryable 408.
    func testXCTFutureCode1000WithUnrelatedMessageStaysInternal() {
        let error = NSError(
            domain: "com.apple.dt.XCTest.XCTFuture",
            code: 1000,
            userInfo: [NSLocalizedDescriptionKey: "Swipe (v2) failed for an unrelated reason."]
        )
        XCTAssertEqual(SwipeRouteHandlerV2.classify(error).type, .internal)
    }

    // A timeout must be classified as .timeout AND carry the real Apple message (this is what fixes
    // the "Unknown error" gap). Asserting the message alone is insufficient: classify() builds the
    // message identically in every branch, so it would pass even on the .internal fallthrough.
    func testClassifiedTimeoutPreservesTheRealMessage() {
        let error = NSError(
            domain: "com.apple.dt.XCTest.XCTFuture",
            code: 1000,
            userInfo: [NSLocalizedDescriptionKey: "Timed out while evaluating UI query."]
        )
        let result = SwipeRouteHandlerV2.classify(error)
        XCTAssertEqual(result.type, .timeout)
        XCTAssertTrue(result.message.contains("Timed out while evaluating UI query"))
    }
}
