//
//  ViewHierarchyComplexityTests.swift
//
//  Compares the algorithmic complexity of the iOS view-hierarchy walk before and after #3313.
//
//  `elementHierarchy` runs on every Maestro iOS command (assert / tap / wait) to convert the
//  XCTest snapshot into Maestro's `AXElement` tree. `XCUIElementSnapshot.dictionaryRepresentation`
//  is a DEEP call — it serializes the element AND its entire subtree on every access.
//
//    • BEFORE #3313: read the screen ONCE at the root  -> Θ(N)        (deepReadCalls == 1, work == N)
//    • AFTER  #3313: read the screen once PER NODE      -> Θ(N²)       (deepReadCalls == N, work == N·(N+1)/2)
//
//  Both sides are exercised against real production code (the pre-#3313 body uses the real
//  `AXElement(_:)` initializer; the post-#3313 case drives the real `ViewHierarchyHandler`),
//  with an instrumented fake `XCUIElementSnapshot` counting the work.
//
//  Run just this class:
//    xcodebuild test -scheme maestro-driver-ios \
//      -destination 'platform=iOS Simulator,name=iPhone 16' \
//      -only-testing:maestro-driver-iosUITests/ViewHierarchyComplexityTests
//

import XCTest
import CoreGraphics
import MaestroDriverLib

/// Minimal `XCUIElementSnapshot` whose deep `dictionaryRepresentation` is counted. The
/// attributes the walk reads via KVC (`isRemote`, `visibleFrame`) are exposed as `@objc`
/// members so `responds(to:)` / `value(forKey:)` behave like a real snapshot.
final class FakeSnapshot: NSObject, XCUIElementSnapshot {
    static var deepReadCalls = 0     // times `dictionaryRepresentation` was accessed
    static var nodesSerialized = 0   // total nodes touched by deep serialization
    static func resetCounters() { deepReadCalls = 0; nodesSerialized = 0 }

    private let id: String
    private let frameDict: AXFrame
    private let windowCtxID: Double
    private let visible: AXFrame?
    private let remote: Bool
    let childNodes: [FakeSnapshot]

    init(id: String, frame: AXFrame = ["X": 0, "Y": 0, "Width": 100, "Height": 100],
         windowContextID: Double = 0, visibleFrame: AXFrame? = nil, isRemote: Bool = false,
         children: [FakeSnapshot] = []) {
        self.id = id; self.frameDict = frame; self.windowCtxID = windowContextID
        self.visible = visibleFrame; self.remote = isRemote; self.childNodes = children
    }

    var children: [XCUIElementSnapshot] { childNodes }
    var elementType: XCUIElement.ElementType { .other }
    var identifier: String { id }
    var label: String { "" }
    var title: String { "" }
    var value: Any? { nil }
    var placeholderValue: String? { nil }
    var isEnabled: Bool { true }
    var isSelected: Bool { false }
    var hasFocus: Bool { false }
    var frame: CGRect {
        CGRect(x: frameDict["X"] ?? 0, y: frameDict["Y"] ?? 0,
               width: frameDict["Width"] ?? 0, height: frameDict["Height"] ?? 0)
    }
    var horizontalSizeClass: XCUIElement.SizeClass { .unspecified }
    var verticalSizeClass: XCUIElement.SizeClass { .unspecified }

    var dictionaryRepresentation: [XCUIElement.AttributeName: Any] {
        Self.deepReadCalls += 1
        return serialize()
    }
    private func serialize() -> [XCUIElement.AttributeName: Any] {
        Self.nodesSerialized += 1
        return [
            XCUIElement.AttributeName(rawValue: "identifier"): id,
            XCUIElement.AttributeName(rawValue: "frame"): frameDict,
            XCUIElement.AttributeName(rawValue: "windowContextID"): windowCtxID,
            XCUIElement.AttributeName(rawValue: "children"): childNodes.map { $0.serialize() },
        ]
    }

    @objc var isRemote: Bool { remote }
    @objc var visibleFrame: NSValue {
        let f = visible ?? frameDict
        return NSValue(cgRect: CGRect(x: f["X"] ?? 0, y: f["Y"] ?? 0,
                                      width: f["Width"] ?? 0, height: f["Height"] ?? 0))
    }
}

@MainActor
final class ViewHierarchyComplexityTests: XCTestCase {

    /// A linear chain of `n` nodes (depth == n) — the worst case, and the realistic shape of
    /// long scrollable document / list screens.
    private func chain(_ n: Int) -> FakeSnapshot {
        var node = FakeSnapshot(id: "n\(n - 1)")
        for i in stride(from: n - 2, through: 0, by: -1) {
            node = FakeSnapshot(id: "n\(i)", children: [node])
        }
        return node
    }

    /// BEFORE #3313: `elementHierarchy(xcuiElement:)` was simply
    ///     `let dict = snapshot.dictionaryRepresentation; return AXElement(dict)`
    /// i.e. ONE deep read at the root, then build the tree from that in-memory dictionary.
    /// Total work: Θ(N). We exercise that exact body via the real `AXElement(_:)` initializer.
    func test_before3313_readsScreenOnce_isLinear() {
        for n in [16, 64, 256, 512] {
            FakeSnapshot.resetCounters()
            _ = AXElement(chain(n).dictionaryRepresentation) // the pre-#3313 conversion body
            XCTAssertEqual(FakeSnapshot.deepReadCalls, 1,
                           "before #3313: the screen is read once, at the root. n=\(n)")
            XCTAssertEqual(FakeSnapshot.nodesSerialized, n,
                           "before #3313: linear work == N. n=\(n)")
        }
    }

    /// AFTER #3313 (current `main`): `elementHierarchy` calls the deep
    /// `dictionaryRepresentation` once PER NODE, so total work is exactly N·(N+1)/2 -> Θ(N²).
    /// We drive the real handler.
    func test_after3313_readsScreenPerNode_isQuadratic() {
        for n in [16, 64, 256, 512] {
            FakeSnapshot.resetCounters()
            _ = ViewHierarchyHandler().elementHierarchy(
                snapshot: chain(n), inheritedOffset: .zero, parentWindowContextID: nil
            )
            XCTAssertEqual(FakeSnapshot.deepReadCalls, n,
                           "after #3313: the screen is read once per node. n=\(n)")
            XCTAssertEqual(FakeSnapshot.nodesSerialized, n * (n + 1) / 2,
                           "after #3313: total work == N·(N+1)/2 -> Θ(N²). n=\(n)")
        }
    }
}
