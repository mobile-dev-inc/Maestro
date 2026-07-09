import XCTest
@testable import MaestroDriverLib

final class ForegroundAppLookupStrategyTests: XCTestCase {

    func testEmptyPhysicalDeviceCandidatesUseOnDeviceDiscovery() {
        XCTAssertEqual(
            ForegroundAppLookupStrategy(appIds: []),
            .discoverOnDevice
        )
    }

    func testSimulatorCandidatesRemainConstrained() {
        let appIds = ["com.example.first", "com.example.second"]

        XCTAssertEqual(
            ForegroundAppLookupStrategy(appIds: appIds),
            .constrainedTo(appIds)
        )
    }
}
