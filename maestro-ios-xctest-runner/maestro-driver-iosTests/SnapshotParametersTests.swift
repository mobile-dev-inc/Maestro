import XCTest

/// Tests to verify XCTest's accessibility snapshot parameters and swizzling behavior.
/// These tests run as unit tests hosted in the simulator, giving access to XCTest runtime.
/// The test scheme sets `snapshotKeyHonorModalViews=false` to trigger swizzling.
final class SnapshotParametersTests: XCTestCase {

    /// Tests that the default snapshot request parameters exist with expected values.
    /// If Apple changes the private API, this test will fail.
    func testDefaultParametersExist() {
        let parameterDictionary = AXClientProxy.sharedClient().defaultParameters()

        XCTAssertNotNil(parameterDictionary, "Parameter dictionary should not be nil - XCAXClient_iOS may have changed")

        guard let params = parameterDictionary else {
            return
        }

        // Verify expected keys exist
        let expectedKeys = ["maxChildren", "maxDepth", "maxArrayCount", "traverseFromParentsToChildren"]
        for key in expectedKeys {
            XCTAssertNotNil(params[key], "Expected parameter key '\(key)' is missing - Apple may have changed the API")
        }
    }

    /// Tests that the default parameter values match expected XCTest defaults.
    func testDefaultParameterValues() {
        guard let params = AXClientProxy.sharedClient().defaultParameters() else {
            XCTFail("Parameter dictionary is nil")
            return
        }


        XCTAssertEqual(params["maxChildren"] as? Int, 2147483647,
                       "maxChildren default changed - swizzling may need update")
        XCTAssertEqual(params["maxDepth"] as? Int, 2147483647,
                       "maxDepth default changed - swizzling may need update")
        XCTAssertEqual(params["maxArrayCount"] as? Int, 2147483647,
                       "maxArrayCount default changed - swizzling may need update")
        XCTAssertEqual(params["traverseFromParentsToChildren"] as? Int, 1,
                       "traverseFromParentsToChildren default changed - swizzling may need update")
    }

    // MARK: - Swizzling Tests

    /// Tests that the custom parameter storage works correctly.
    /// This verifies FBSetCustomParameterForElementSnapshot was called during +load.
    func testCustomParameterWasSet() {
        let customValue = FBGetCustomParameterForElementSnapshot("snapshotKeyHonorModalViews")

        XCTAssertNotNil(customValue,
            "Custom parameter 'snapshotKeyHonorModalViews' was not set - swizzling +load may have failed")
        XCTAssertEqual(customValue as? NSNumber, NSNumber(value: 0),
            "Custom parameter 'snapshotKeyHonorModalViews' should be 0 (false)")
    }

    /// Tests that the swizzled defaultParameters includes snapshotKeyHonorModalViews=0.
    /// This is the critical test that verifies swizzling is working end-to-end.
    func testSwizzledDefaultParametersIncludesCustomParameter() {
        guard let params = AXClientProxy.sharedClient().defaultParameters() else {
            XCTFail("Parameter dictionary is nil - XCAXClient_iOS may have changed")
            return
        }

        // After swizzling, defaultParameters should include our custom parameter
        let snapshotKeyHonorModalViews = params["snapshotKeyHonorModalViews"]

        XCTAssertNotNil(snapshotKeyHonorModalViews,
            "snapshotKeyHonorModalViews key is missing from swizzled defaultParameters - " +
            "swizzling may have failed or Apple changed the API")
        XCTAssertEqual(snapshotKeyHonorModalViews as? NSNumber, NSNumber(value: 0),
            "snapshotKeyHonorModalViews should be 0 (disabled) after swizzling")
    }

    /// Tests that XCTElementQuery class exists (required for snapshotParameters swizzling).
    func testXCTElementQueryClassExists() {
        let elementQueryClass: AnyClass? = NSClassFromString("XCTElementQuery")

        XCTAssertNotNil(elementQueryClass,
            "XCTElementQuery class not found - Apple may have renamed or removed it")
    }
}
