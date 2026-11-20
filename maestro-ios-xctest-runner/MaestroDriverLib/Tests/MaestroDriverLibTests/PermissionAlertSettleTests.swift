import XCTest
@testable import MaestroDriverLib

final class PermissionAlertSettleTests: XCTestCase {

    /// Plays back `frames` one per screenshot and advances a fake clock on every sleep.
    private final class FakeScreen {
        private var frames: [Int]
        private(set) var screenshots = 0
        var elapsed: TimeInterval = 0

        init(_ frames: [Int]) { self.frames = frames }

        func screenshot() -> Int {
            screenshots += 1
            return frames.count > 1 ? frames.removeFirst() : frames[0]
        }
    }

    private func wait(_ screen: FakeScreen, timeout: TimeInterval = 2.0) async -> Bool {
        let start = Date(timeIntervalSinceReferenceDate: 0)
        return await PermissionAlertSettle(timeout: timeout, pollInterval: 0.1).waitUntilScreenIsStatic(
            screenshot: { screen.screenshot() },
            sleep: { screen.elapsed += $0 },
            now: { start.addingTimeInterval(screen.elapsed) }
        )
    }

    func testScreenAlreadyStatic_settlesAfterTwoMatchingScreenshots() async {
        let screen = FakeScreen([7])

        let settled = await wait(screen)

        XCTAssertTrue(settled)
        XCTAssertEqual(screen.screenshots, 2)
    }

    func testAlertStillAnimating_waitsUntilTheScreenStopsChanging() async {
        let screen = FakeScreen([1, 2, 3, 4, 4])

        let settled = await wait(screen)

        XCTAssertTrue(settled)
        XCTAssertEqual(screen.screenshots, 5)
    }

    func testScreenNeverStops_givesUpAfterTheTimeout() async {
        let screen = FakeScreen(Array(1...100))

        let settled = await wait(screen, timeout: 2.0)

        XCTAssertFalse(settled)
        XCTAssertEqual(screen.elapsed, 2.0, accuracy: 0.11)
    }
}
