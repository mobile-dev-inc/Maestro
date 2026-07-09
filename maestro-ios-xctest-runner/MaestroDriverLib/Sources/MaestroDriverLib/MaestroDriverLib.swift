// MaestroDriverLib
// A framework for UI automation logic that doesn't depend on XCUITest

// Re-export all public types
@_exported import Foundation

/// Selects how XCTest routes resolve the foreground app. Physical-device callers cannot use
/// simctl to enumerate apps and deliberately send an empty list; simulator callers send a
/// constrained list so transient system overlays do not win foreground-app selection.
public enum ForegroundAppLookupStrategy: Equatable {
    case discoverOnDevice
    case constrainedTo([String])

    public init(appIds: [String]) {
        self = appIds.isEmpty ? .discoverOnDevice : .constrainedTo(appIds)
    }
}
