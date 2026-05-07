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

        // POC unlock: from iOS 13+, snapshot/query parameter dicts must include
        // `snapshotKeyHonorModalViews`. WDA discovered this in their PR #523 — without
        // it, cross-process modal subtrees come through redacted (every node a bare
        // `Other`, no labels, no buttons). Default value is NO; just ensuring the key
        // is present is the unlock.
        AXClientSwizzler.overwriteDefaultParameters["snapshotKeyHonorModalViews"] = 0
        AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = 60
    }

    override class func setUp() {
        logger.trace("setUp")
    }

    func testHttpServer() async throws {
        let server = XCTestHTTPServer()
        maestro_driver_iosUITests.logger.info("Will start HTTP server")
        try await server.start()
    }

    // MARK: - POC: validate that XCUIElement.tap() resolves cross-process screenPoint
    //
    // Validates option B (tap-by-element-handle) for HealthKit-class privacy sheets.
    // Uses Appium WebDriverAgent's pattern: structural type-based queries to find
    // the auth-sheet container (which iOS 16+ does NOT privacy-gate), then
    // drill into it for buttons. Tap via [element tap] so Apple resolves the
    // screen point — that's the mechanism Maestro can adopt instead of computing
    // pixels from possibly-wrong frames.
    //
    // Run only this test:
    //   ./poc-elementtap-run.sh
    //
    // Prereqs: HK entitlements baked into the binary by full Flutter codesigning
    // (the run script handles this). HK auth state must be unauthorized so the
    // sheet appears; the script uninstalls beforehand to ensure that.
    func testHealthKitTapPOC() throws {
        let app = XCUIApplication(bundleIdentifier: "com.example.example")
        app.terminate()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10),
                      "Demo app failed to enter foreground — is it installed on this simulator?")

        // Step 1: in-process tap (not privacy-gated).
        let healthAccessButton = app.buttons["Health Access"]
        XCTAssertTrue(healthAccessButton.waitForExistence(timeout: 5))
        healthAccessButton.tap()
        NSLog("[POC] Tapped 'Health Access' — auth sheet should now appear.")
        Thread.sleep(forTimeInterval: 3)

        // ============================================================
        // WDA-SHAPE STRONG-REFERENCE CACHE TEST (matches what we'd ship)
        // ============================================================
        // O(N) hierarchy build: one dictionaryRepresentation at root,
        // walk snapshot + dict + live in lockstep, store live XCUIElement
        // refs in a UUID-keyed cache. Then tap by id.
        var elementCache: [String: XCUIElement] = [:]
        var idByLabel: [String: String] = [:]

        func buildCache(app: XCUIApplication) throws -> Int {
            elementCache.removeAll(); idByLabel.removeAll()
            let rootSnap = try app.snapshot()
            let rootDict = rootSnap.dictionaryRepresentation        // ONCE — O(N) total
            walk(snap: rootSnap, dict: rootDict, live: app)
            return elementCache.count
        }

        func walk(snap: XCUIElementSnapshot,
                  dict: [XCUIElement.AttributeName: Any],
                  live: XCUIElement) {
            let id = UUID().uuidString
            elementCache[id] = live
            if let label = dict[XCUIElement.AttributeName(rawValue: "label")] as? String,
               !label.isEmpty, idByLabel[label] == nil {
                idByLabel[label] = id
            }

            let childDicts = (dict[XCUIElement.AttributeName(rawValue: "children")]
                              as? [[XCUIElement.AttributeName: Any]]) ?? []
            var counters: [XCUIElement.ElementType: Int] = [:]
            for (childSnap, childDict) in zip(snap.children, childDicts) {
                let type = childSnap.elementType
                let idx = counters[type, default: 0]
                counters[type] = idx + 1
                let childLive = live.children(matching: type).element(boundBy: idx)  // lazy, O(1)
                walk(snap: childSnap, dict: childDict, live: childLive)
            }
        }

        let buildStart = Date()
        let nodeCount = try buildCache(app: app)
        let buildMs = Int(Date().timeIntervalSince(buildStart) * 1000)
        NSLog("[POC] cache built — \(nodeCount) nodes in \(buildMs)ms; 'Turn On All' indexed: \(idByLabel["Turn On All"] != nil)")

        // Tap 'Turn On All' via cache lookup
        guard let turnOnId = idByLabel["Turn On All"], let turnOnEl = elementCache[turnOnId] else {
            XCTFail("Cache missing 'Turn On All' — walk did not visit it"); return
        }
        NSLog("[POC] Tapping 'Turn On All' via cache id=\(turnOnId)")
        turnOnEl.tap()
        Thread.sleep(forTimeInterval: 1.5)

        let postLabels = collectLabels(from: try app.snapshot().dictionaryRepresentation)
        let flipped = postLabels.contains("Turn Off All")
        NSLog("[POC] post-tap 'Turn Off All' present: \(flipped)")
        XCTAssertTrue(flipped, "Cache-based tap did not flip toggle — option B WDA-shape FAILED")

        // Re-build cache (simulates next hierarchy fetch), tap 'Allow'
        let rebuildStart = Date()
        _ = try buildCache(app: app)
        let rebuildMs = Int(Date().timeIntervalSince(rebuildStart) * 1000)
        NSLog("[POC] cache rebuilt in \(rebuildMs)ms; 'Allow' indexed: \(idByLabel["Allow"] != nil)")

        guard let allowId = idByLabel["Allow"], let allowEl = elementCache[allowId] else {
            XCTFail("Cache missing 'Allow' on rebuild"); return
        }
        NSLog("[POC] Tapping 'Allow' via cache id=\(allowId)")
        allowEl.tap()
        Thread.sleep(forTimeInterval: 1.5)

        let stillUp = collectLabels(from: try app.snapshot().dictionaryRepresentation)
            .contains(where: { $0.contains("would like to access") })
        NSLog("[POC] post-Allow sheet still up: \(stillUp)")
        XCTAssertFalse(stillUp, "Sheet did not dismiss")
        NSLog("[POC] *** ✓✓ FULL FLOW VALIDATED via WDA-shape strong-reference cache ***")
        return  // skip the legacy assertions below

        #if false
        // -- everything below this point is the v6 path-based flow (kept for reference) --

        // SNAPSHOT PROBE — does PR #3250's path see the auth sheet's labels on this iOS?
        Thread.sleep(forTimeInterval: 3)  // give the cross-process sheet time to render
        var pathToTurnOnAll: [(type: Int, idx: Int)] = []
        do {
            let snapshot = try app.snapshot()
            let dict = snapshot.dictionaryRepresentation
            let labels = collectLabels(from: dict)
            let interesting = labels.filter { $0.contains("Turn On All") || $0 == "Allow" || $0.contains("would like to access") }
            NSLog("[POC] snapshot total labels: \(labels.count); auth-sheet matches: \(interesting)")

            // PATH PROBE — find "Turn On All" in the snapshot and record its (type, idx) chain.
            if let path = findPath(in: dict, target: "Turn On All") {
                pathToTurnOnAll = path
                NSLog("[POC] snapshot path to 'Turn On All': \(path.map { "(type=\($0.type), idx=\($0.idx))" }.joined(separator: " → "))")
            } else {
                NSLog("[POC] PATH NOT FOUND in snapshot")
            }
        } catch {
            NSLog("[POC] snapshot() threw: \(error)")
        }

        // RESOLUTION PROBE — does that path resolve into a live, tappable XCUIElement,
        // even though label queries are filtered? This is the actual option-B test.
        if !pathToTurnOnAll.isEmpty {
            var node: XCUIElement = app
            for (i, step) in pathToTurnOnAll.enumerated() {
                let type = XCUIElement.ElementType(rawValue: UInt(step.type)) ?? .any
                node = node.children(matching: type).element(boundBy: step.idx)
                NSLog("[POC] resolve step \(i): type=\(step.type) idx=\(step.idx) exists=\(node.exists)")
                if !node.exists { break }
            }
            if node.exists {
                NSLog("[POC] resolved live element — calling .tap()")
                node.tap()
                Thread.sleep(forTimeInterval: 1.5)

                // PROOF: maestro flow asserts 'Turn Off All' appears after tapping 'Turn On All'.
                // If the snapshot now contains 'Turn Off All', the tap actually fired in the
                // cross-process auth sheet — option B's mechanism works.
                let postTapLabels = (try? collectLabels(from: app.snapshot().dictionaryRepresentation)) ?? []
                let toggleFlipped = postTapLabels.contains("Turn Off All")
                NSLog("[POC] post-tap snapshot labels include 'Turn Off All': \(toggleFlipped)")

                if toggleFlipped {
                    NSLog("[POC] *** ✓ OPTION B IS VIABLE FOR HEALTHKIT ***")
                    NSLog("[POC] *** Path-based resolution + [element tap] correctly hit the cross-process button ***")

                    // Bonus: try to also tap 'Allow' via path resolution.
                    let dict2 = (try? app.snapshot().dictionaryRepresentation) ?? [:]
                    if let allowPath = findPath(in: dict2, target: "Allow") {
                        NSLog("[POC] resolving 'Allow' path: \(allowPath.map { "(\($0.type),\($0.idx))" }.joined(separator: " → "))")
                        var allowNode: XCUIElement = app
                        for step in allowPath {
                            let t = XCUIElement.ElementType(rawValue: UInt(step.type)) ?? .any
                            allowNode = allowNode.children(matching: t).element(boundBy: step.idx)
                        }
                        if allowNode.exists {
                            allowNode.tap()
                            Thread.sleep(forTimeInterval: 1.5)
                            let dismissed = !((try? collectLabels(from: app.snapshot().dictionaryRepresentation)) ?? [])
                                .contains(where: { $0.contains("would like to access") })
                            NSLog("[POC] post-Allow: sheet dismissed = \(dismissed)")
                            if dismissed {
                                NSLog("[POC] *** ✓✓ FULL FLOW VALIDATED: Turn On All + Allow both via option B ***")
                            }
                        }
                    }
                } else {
                    NSLog("[POC] *** option B mechanism dispatched a tap but it did NOT register on the auth sheet ***")
                }
            } else {
                NSLog("[POC] *** path resolution collapsed in live tree — option B NOT viable for redacted surfaces ***")
            }
        }
        // Stop here — the probe is the answer; the rest of the test is moot for this validation.
        return

        // Step 2: now that snapshotKeyHonorModalViews is in the params,
        // the simple label query SHOULD work — no structural workaround needed.
        let turnOnAll = app.buttons["Turn On All"]
        if !turnOnAll.waitForExistence(timeout: 8) {
            dumpDiagnostics(app: app, label: "Turn On All still not found after honor-modal flag")
            XCTFail("'Turn On All' still not findable — snapshotKeyHonorModalViews wasn't the unlock")
            return
        }
        turnOnAll.tap()
        NSLog("[POC] Tapped 'Turn On All' via [element tap].")

        // Step 3: 'Allow' button — try the testID first (set by Apple on the auth sheet).
        Thread.sleep(forTimeInterval: 0.5)
        let allowById = app.buttons["UIA.Health.Allow.Button"]
        let allowByLabel = app.buttons["Allow"]
        let allow: XCUIElement
        if allowById.waitForExistence(timeout: 5) {
            allow = allowById
        } else if allowByLabel.waitForExistence(timeout: 3) {
            allow = allowByLabel
        } else {
            dumpDiagnostics(app: app, label: "Allow not found")
            XCTFail("'Allow' button not findable")
            return
        }
        allow.tap()
        NSLog("[POC] Tapped 'Allow' via [element tap].")

        // Step 5: assert the auth sheet's title is gone.
        let title = app.staticTexts
            .matching(NSPredicate(format: "label CONTAINS 'would like to access'"))
            .firstMatch
        XCTAssertFalse(title.waitForExistence(timeout: 3),
                       "Auth sheet did NOT dismiss — option B mechanism is INSUFFICIENT for HealthKit-class surfaces")
        NSLog("[POC] ✓ Sheet dismissed — XCUIElement.tap() handled the cross-process surface.")
        #endif
    }

    /// WDA-style structural query: look for any sheet/alert/other-typed top-level container.
    /// HealthKit's privacy sheet has shown up as XCUIElementTypeOther on iOS 17+;
    /// older iOS used XCUIElementTypeSheet. We also accept XCUIElementTypeAlert.
    private func waitForCrossProcessContainer(in app: XCUIApplication,
                                              timeout: TimeInterval) -> XCUIElement? {
        let typeIDs = [
            XCUIElement.ElementType.sheet.rawValue,
            XCUIElement.ElementType.alert.rawValue,
            XCUIElement.ElementType.other.rawValue,
        ]
        let typeList = typeIDs.map { String($0) }.joined(separator: ", ")
        let predicate = NSPredicate(format: "elementType IN { \(typeList) }")

        // We require the container to actually contain at least one button — that's
        // how we tell the auth sheet apart from the general-purpose 'Other' background views.
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let candidates = app.descendants(matching: .any)
                .matching(predicate)
                .allElementsBoundByIndex
            for c in candidates where c.exists && c.buttons.count >= 1 {
                // Crude heuristic: the auth sheet has multiple buttons (Turn On All + per-category toggles + Allow).
                // Avoid matching tiny container views that just happen to wrap a single nav button.
                if c.buttons.count >= 2 {
                    return c
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        // Fallback: even a single-button container is OK if nothing better appeared.
        return app.descendants(matching: .any).matching(predicate)
            .allElementsBoundByIndex.first { $0.buttons.count >= 1 }
    }

    /// Inside an already-structurally-resolved container, label-based queries are NOT gated.
    /// This mirrors WDA's `[alertElement descendantsMatchingType:Button] matchingPredicate:label==X`.
    private func findButtonStructurally(in container: XCUIElement,
                                        label: String,
                                        fallbackLabel: String? = nil,
                                        timeout: TimeInterval) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let candidates: [String] = fallbackLabel.map { [label, $0] } ?? [label]
            for lbl in candidates {
                let pred = NSPredicate(format: "label == %@", lbl)
                let match = container.descendants(matching: .button)
                    .matching(pred)
                    .firstMatch
                if match.exists { return match }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
        return nil
    }

    /// Walk the snapshot dict, find an element whose label exactly matches `target`,
    /// return the `(elementType, indexAmongSameTypeSiblings)` chain from the root.
    private func findPath(in dict: [XCUIElement.AttributeName: Any],
                          target: String,
                          accumulator: [(type: Int, idx: Int)] = []) -> [(type: Int, idx: Int)]? {
        if let label = dict[XCUIElement.AttributeName(rawValue: "label")] as? String, label == target {
            return accumulator
        }
        guard let children = dict[XCUIElement.AttributeName(rawValue: "children")]
            as? [[XCUIElement.AttributeName: Any]] else { return nil }
        var typeCounters: [Int: Int] = [:]
        for child in children {
            let type = child[XCUIElement.AttributeName(rawValue: "elementType")] as? Int ?? 0
            let idx = typeCounters[type, default: 0]
            typeCounters[type] = idx + 1
            if let found = findPath(in: child,
                                    target: target,
                                    accumulator: accumulator + [(type, idx)]) {
                return found
            }
        }
        return nil
    }

    private func collectLabels(from dict: [XCUIElement.AttributeName: Any]) -> [String] {
        var out: [String] = []
        if let label = dict[XCUIElement.AttributeName(rawValue: "label")] as? String, !label.isEmpty {
            out.append(label)
        }
        if let title = dict[XCUIElement.AttributeName(rawValue: "title")] as? String, !title.isEmpty {
            out.append(title)
        }
        if let children = dict[XCUIElement.AttributeName(rawValue: "children")]
            as? [[XCUIElement.AttributeName: Any]] {
            for c in children { out += collectLabels(from: c) }
        }
        return out
    }

    private func dumpDiagnostics(app: XCUIApplication,
                                 container: XCUIElement? = nil,
                                 label: String) {
        NSLog("[POC] === diagnostics: \(label) ===")
        NSLog("[POC] app.debugDescription:\n\(app.debugDescription)")
        if let c = container {
            NSLog("[POC] container.debugDescription:\n\(c.debugDescription)")
        }
    }

    override class func tearDown() {
        logger.trace("tearDown")
    }
}
