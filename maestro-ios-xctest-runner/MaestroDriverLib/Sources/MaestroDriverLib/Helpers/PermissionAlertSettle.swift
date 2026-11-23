import Foundation

/// The notifications permission alert shows up in SpringBoard's hierarchy at its final
/// frame while it is still animating in. A tap in that window is dropped, or on a slow
/// simulator reaches the app underneath. Before tapping it, wait for the screen to stop
/// changing, the same check Maestro makes before any other tap.
public struct PermissionAlertSettle {
    public let timeout: TimeInterval
    public let pollInterval: TimeInterval

    public init(timeout: TimeInterval = 2.0, pollInterval: TimeInterval = 0.1) {
        self.timeout = timeout
        self.pollInterval = pollInterval
    }

    /// Returns true once two screenshots `pollInterval` apart match, false after `timeout`.
    public func waitUntilScreenIsStatic<Frame: Equatable>(
        screenshot: () -> Frame,
        sleep: (TimeInterval) async -> Void,
        now: () -> Date = Date.init
    ) async -> Bool {
        let deadline = now().addingTimeInterval(timeout)
        var previous = screenshot()
        while now() < deadline {
            await sleep(pollInterval)
            let current = screenshot()
            if current == previous { return true }
            previous = current
        }
        return false
    }
}
