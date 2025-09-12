import XCTest
import UIKit
@testable import maestro_driver_lib

final class ScreenSizeCacheTests: XCTestCase {

    override func setUp() {
        super.setUp()
        // Clear static cache before each test
        ScreenSizeCache.set(bundleId: nil, orientation: nil, size: nil)
    }

    override func tearDown() {
        // Clear after each test too (defensive)
        ScreenSizeCache.set(bundleId: nil, orientation: nil, size: nil)
        super.tearDown()
    }

    func test_initiallyEmpty_returnsNil() {
        XCTAssertNil(ScreenSizeCache.getIfValid(bundleId: "com.foo", orientation: .portrait))
    }

    func test_setAndGet_exactMatchHits() {
        // when
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (100, 200))

        // then
        let cached = ScreenSizeCache.getIfValid(bundleId: "com.foo", orientation: .portrait)
        XCTAssertEqual(cached?.0, 100)
        XCTAssertEqual(cached?.1, 200)
    }

    func test_miss_whenForegroundBundleDiffers() {
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (100, 200))

        XCTAssertNil(ScreenSizeCache.getIfValid(bundleId: "com.bar", orientation: .portrait))
    }

    func test_miss_whenOrientationDiffers() {
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (100, 200))

        XCTAssertNil(ScreenSizeCache.getIfValid(bundleId: "com.foo", orientation: .landscapeLeft))
    }

    func test_miss_whenQueryHasNilButCacheHasNonNilBundle() {
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (100, 200))

        // nil != "com.foo" -> miss
        XCTAssertNil(ScreenSizeCache.getIfValid(bundleId: nil, orientation: .portrait))
    }

    func test_overwrite_replacesStoredValues() {
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (100, 200))
        ScreenSizeCache.set(bundleId: "com.foo", orientation: .portrait, size: (300, 400))

        let cached = ScreenSizeCache.getIfValid(bundleId: "com.foo", orientation: .portrait)
        XCTAssertEqual(cached?.0, 300)
        XCTAssertEqual(cached?.1, 400)
    }
}
