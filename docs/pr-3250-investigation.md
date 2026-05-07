# PR #3250 — HealthKit cross-process tap investigation

**Branch the investigation lives on:** `poc-elementtap-healthkit` (off `main`).
**Date:** 2026-05-06.
**Goal:** decide whether PR #3250's snapshot-walking offset math is the right fix
for the customer's HealthKit auth-sheet bug, or whether a cheaper / more general
fix exists.

---

## The customer bug, restated

On iOS, when a system permission sheet (HealthKit auth, etc.) is presented:

- The sheet's elements *appear* in `XCUIElement.snapshot()` so Maestro's
  hierarchy parser finds buttons like "Turn On All", "Allow".
- But the elements' `frame` is reported in the **foreign process's window-local
  coordinates**, not screen coordinates.
- Maestro's tap path takes the snapshot's frame, computes a pixel center, sends
  `(x, y)` over HTTP to the runner, and synthesizes a pointer event at those
  pixels.
- Pixel coords are wrong → tap lands off-button → flow reports
  `tapOn: "Turn On All" COMPLETED` but nothing changes; subsequent
  `assertVisible: "Turn Off All"` fails.

This was reproduced today with stock `maestro 2.4.0` against the demo app on
iOS 26.0 simulator.

## Three open questions, three experiments

### Q1. Is the "snapshot vs. live query" asymmetry real on iOS 26?

**Why it matters:** PR #3250 reads the snapshot tree to find elements. If the
snapshot is privacy-redacted on iOS 26, the PR doesn't work either.

**Probe:** POC test took `XCUIElement.snapshot().dictionaryRepresentation` after
the auth sheet appeared, dumped all `label` and `title` fields.

**Result (POC v4):**
```
[POC] snapshot total labels: 60
[POC] auth-sheet matches: [
  "Demo App would like to access and update your Health data.",
  "Turn On All",
  "Allow Demo App to read",
  "Heart Rate", "Heart Rate", "Heart Rate",
  "Steps", "Steps", "Steps",
  "Allow", "Allow",
  "Don't Allow", "Don't Allow"
]
```

**Conclusion:** snapshot is fully exposed on iOS 26. PR #3250's read path is
intact.

### Q2. Does the workspace config flag solve it on the current arch?

**Why it matters:** Maestro already plumbs `platform.ios.snapshotKeyHonorModalViews`
end-to-end (`WorkspaceConfig.kt:50` →
`MaestroSessionManager.kt:377` → `LocalXCTestInstaller.kt:211` →
`XCRunnerCLIUtils.kt:131-132` → ObjC `XCAXClient_iOS+FBSnapshotReqParams.m`
`+load`). When set to `false`, the runner swizzles **both**
`XCAXClient_iOS.defaultParameters` AND `XCTElementQuery.snapshotParameters`
(WDA's pattern). If this alone fixed it, no code change would be needed.

**Probe:** added
```yaml
platform:
  ios:
    snapshotKeyHonorModalViews: false
```
to `e2e/demo_app/.maestro/config.yaml`, ran the PR's flow file
(`fail_health_access.yaml`) via stock `maestro 2.4.0`.

**Result:**
```
> Flow fail_health_access
Tap on "Health Access"... COMPLETED
Tap on "Turn On All"... COMPLETED
Assert that "Turn Off All" is visible... FAILED
```

**Conclusion:** flag unlocks element *discovery* (Maestro finds "Turn On All"
to tap). Tap is dispatched. But the pixel landed off-button — the auth sheet's
toggle didn't flip — so the next assertion fails. **The flag is necessary but
not sufficient.** The bug is the cross-process frame coordinate, not the
visibility of the labels.

### Q3. Does `[XCUIElement tap]` on a path-resolved element work for HealthKit?

**Why it matters:** WDA-style "structural path resolution + `[element tap]`"
was the leading alternative to PR #3250's offset math. If Apple's
`XCUIElementHitPointCoordinate.screenPoint` correctly resolves the cross-process
coordinate (as it does on most surfaces), this would generalize beyond
HealthKit and also fix issues #560, #1924, #2065, #1227, #3093.

**Probe:** POC test
1. Walked the snapshot to find "Turn On All", recorded its
   `(elementType, indexAmongTypeSiblings)` chain.
2. Resolved on the live tree via
   `node.children(matching: type).element(boundBy: idx)` for each step.
3. Called `.tap()` on the resolved element.
4. Verified `Turn Off All` then appears in the snapshot (the toggle flipped).
5. Repeated for "Allow", verified the sheet dismisses.

**Result (POC v6):**
```
[POC] snapshot path to 'Turn On All': (4,0) → (1,1) → (1,1) → (1,0)×11 → (26,0) → (75,0) → (48,0)
[POC] resolve step 0–16: exists=true   (every step including Table → Cell → StaticText)
[POC] resolved live element — calling .tap()
[POC] post-tap snapshot labels include 'Turn Off All': true
[POC] *** ✓ OPTION B IS VIABLE FOR HEALTHKIT ***
[POC] post-Allow: sheet dismissed = true
[POC] *** ✓✓ FULL FLOW VALIDATED: Turn On All + Allow both via option B ***
** TEST SUCCEEDED **
```

**Conclusion:** option B works end-to-end for HealthKit on iOS 26. Apple's
`[element tap]` correctly resolves the screen point even for the cross-process
auth sheet. The privacy filter that blocks `app.buttons["Turn On All"]` does
**not** block structural index-based traversal, and it does **not** affect
`[element tap]` once you have the handle.

---

## What was wrong in earlier guesses

| Earlier theory | Actual finding |
|---|---|
| "Live `XCUIElementQuery` collapses the foreign subtree to opaque `Other`" | **False.** All 17 typed steps including Table/Cell/StaticText resolved in the live tree. The collapse seen in `app.debugDescription` was an *output* artifact of the redaction, not a structural collapse. |
| "Privacy filter blocks any path into the redacted subtree" | **False.** Filter is on label-based queries (`buttons["X"]`); structural index-based traversal works. |
| "iOS 26 redacts even snapshots, so PR #3250 might not work either" | **False.** Snapshot returns full labels including HK sheet content (Q1). |
| "`snapshotKeyHonorModalViews=false` would fix the customer with current arch" | **False.** It fixes label discovery but the tap still misses because the frame is in foreign-window coords (Q2). |

## What's true

| Path | Finds elements? | Tap correct? | Generalizes? |
|---|---|---|---|
| Stock `main`, no flag | unknown (likely no) | n/a | n/a |
| `main` + workspace flag (today) | ✅ | ❌ pixel misses | n/a |
| `main` + flag + PR #3250 offset math | ✅ | ✅ frame corrected | HK-specific; needs per-surface tuning for #560/#1924/#2065/#1227/#3093 |
| `main` + flag + option B (`[element tap]` via structural path) | ✅ (snapshot) | ✅ Apple resolves screenPoint | yes — same mechanism for all cross-process surfaces |

## Why option B works without offset math

Option B never sends a pixel coordinate over the wire. The driver:
1. Builds the AXElement tree from the snapshot (same as today).
2. Records a `(elementType, indexAmongTypeSiblings)` path per node during the
   walk — single pass, O(N).
3. Returns the tree to the orchestrator with each node carrying an `elementId`.
4. On tap, orchestrator sends the `elementId` to a new `tapElement` route.
5. Driver looks up the path, walks
   `app.children(matching: type).element(boundBy: idx)` for each step → live
   `XCUIElement` reference.
6. Calls `[element tap]`. Apple's `XCUIElementHitPointCoordinate.screenPoint`
   internally resolves the cross-process screen coordinate.

The frame in the snapshot can be wrong (foreign-window coords) and Maestro
never has to care, because the orchestrator no longer computes a pixel.

## Performance

- Hierarchy build: still **O(N)**. The path cache adds O(1) per node (UUID +
  parent-pointer entry); no extra `dictionaryRepresentation` calls, no extra
  AXElement allocations.
- Tap: **O(D)** where D ≈ tree depth (~20–40). Path resolution is one
  `children(matching:).element(boundBy:)` per step; no full-tree query.
  Negligible compared to Apple's event synthesis (~100–300 ms).

For comparison, PR #3250 as currently written has an O(N·D) (worst-case O(N²))
hierarchy build because the new walker calls
`snapshot.dictionaryRepresentation` on every node. Fixing that requires the
H1+H2 changes documented in the original review (single
`dictionaryRepresentation` at root + `skipChildrenFromDict` flag on
`AXElement.init`).

## Open issues option B would fix

Triaged from `gh issue list --search "ios tap" / "ios sheet" / "ios safari"`:

| # | Title | Same root cause? |
|---|---|---|
| 560 | Cannot interact with UI inside Safari Web View Controller (open since 2023) | yes — cross-process frame; reporter even diagnosed `displayID` mismatch |
| 1924 | Unable to interact with a sheet opened from a fullScreenCover (P2) | yes — works only when sheet > 93% screen, classic frame-mismatch symptom |
| 2065 | Studio can't interact with iOS native pop-up when landscape | yes — "nodes exist but flattened to wrong position" |
| 1227 | Cannot tap "Not Now" on iOS keychain save-password dialog | yes — system-process UI |
| 3093 | Native dialog for Sign in with Apple | yes — system-process UI |

Each of these would close as a side-effect of shipping option B, without
per-surface tuning. PR #3250's offset math fixes only HealthKit reliably
(boundary detector keys on `windowContextID + isRemote`, which doesn't apply
to e.g. SFSafariViewController which uses `displayID`).

## Recommendation

Two valid paths.

### (A) Land PR #3250 with H1+H2 perf fixes — "ship the HealthKit fix now"

- Smallest delta from current state.
- Workspace flag stays as-is (already in main, properly plumbed).
- Apply the perf fixes from the original review:
  - **H1**: take `dictionaryRepresentation` once at the root; pass dict + snapshot
    to recursive walker.
  - **H2**: add `skipChildrenFromDict` parameter to
    `AXElement.init(_ dict:, frameOverride:)` so the children-from-dict subtree
    isn't built and immediately discarded.
- Hierarchy build returns to **O(N)**. Customer ships.
- Other 5 issues remain open.

### (B) Build option B per validated POC — "fix the architectural class of bug"

- Replaces PR #3250 entirely.
- Driver phase 1: `ElementPathCache`, single-pass walker emits AXElement +
  records paths, new `tapElement` route, illegal-argument fallback (reusing
  the existing `getHierarchyWithFallback` recovery shape).
- Orchestrator phase 2: `Driver.tap(element:)` with iOS override that prefers
  `tapElement` when `attributes["elementId"]` is present.
- Phased rollout possible (driver phase additive, orchestrator phase opt-in).
- Closes HealthKit + #560 + #1924 + #2065 + #1227 + #3093.
- Bigger lift; needs separate review.

#### Note on the `AXClientSwizzler` install timing

This was reasoned-through in two passes:

**First pass (overcautious, wrong):** install the `maxDepth = 60` swizzle at
boot is a regression risk for any app with hierarchy > 60 levels that currently
succeeds against XCTest's `Int.max` default; their flows would start truncating
from call one.

**Second pass (correct, after checking WDA):** WebDriverAgent's
`FBConfiguration.m` class initializer already sets `maxDepth = 50` at process
boot — *eagerly, by design* — with the comment "50 should be enough for the
majority of the cases. The performance is acceptable for values up to 100."
WDA doesn't wait for failure.

The regression argument breaks down to three buckets:

| Bucket | Today | After eager `maxDepth = 60` |
|---|---|---|
| depth ≤ 60 | snapshot succeeds at `Int.max`, returns full tree | snapshot succeeds at 60, returns same full tree |
| depth > 60, triggers `kAXErrorIllegalArgument` today | first call fails → recovery branch → cap at 60 from then on | succeeds at 60 from call one, no recovery needed |
| depth > 60, *doesn't* trigger today | snapshot returns deep tree; some flow may depend on a > 60-deep element | tree truncated at 60; flow could break |

Only bucket 3 is a real regression risk. WDA's seven-year wager is that bucket
3 is approximately empty in practice. If we adopt the same posture, we should
expose `platform.ios.snapshotMaxDepth` as a workspace config (parallel to the
existing `platform.ios.snapshotKeyHonorModalViews` flag) so anyone in bucket 3
has an override. With that knob, eager install is the correct default.

Plan therefore:
- Install `AXClientSwizzler` swizzle at driver boot with `maxDepth = 60`
  (workspace-config tunable).
- Drop the recovery branch's *set-the-swizzle* line; it's already set.
- Keep the recovery's *change of root* behavior — illegal-argument can also
  fire for reasons other than depth (e.g., snapshotting the application root
  in some XCTest internal state). The change-of-root recovery still helps in
  those cases.

Option B's path resolution doesn't depend on the `maxDepth` override one way
or the other (it walks `children(matching:).element(boundBy:)` per level, no
full-tree snapshot). The eager swizzle is a hardening for the existing
hierarchy build, not a prerequisite for option B.

#### Why path resolution avoids `kAXErrorIllegalArgument`

The `kAXErrorIllegalArgument` failure mode (documented in
`ViewHierarchyHandler.swift:172-178`) is specific to whole-tree snapshot calls
— `XCUIApplication().snapshot().dictionaryRepresentation` and
`XCUIApplication().allElementsBoundByIndex`. Both ask Apple's AX client to
serialize the entire app tree in one shot, which can fail on deep hierarchies.

Path resolution does not do that. Each step is one bounded query
(`children(matching: type).element(boundBy: idx)`) over the current node's
direct children — no full-tree fan-out. POC v6 walked 17 such steps without
incident. If a future Xcode version did make path resolution fail with
illegal-argument, the existing recovery shape (`AXClientSwizzler` set
+ `findRecoveryElement(app.children(matching: .any).firstMatch)` + retry from
that element) is enough; `tapElement`'s fallback should reuse it, not
introduce new machinery.

Recommended sequence: **land (A) now** for the immediate customer fix. Open a
tracked follow-up for **(B)**. The branch `poc-elementtap-healthkit` is the
working proof for (B); the test method `testHealthKitTapPOC` in
`maestro_driver_iosUITests.swift` is the regression test we'd want to keep.

## Artifacts

- POC branch: `poc-elementtap-healthkit` (off main).
- Run script: `poc-elementtap-run.sh` at repo root (uninstalls demo, builds
  with proper Flutter codesign so HK entitlements end up in the LD section,
  installs, runs only `testHealthKitTapPOC`).
- POC test: `maestro-ios-xctest-runner/maestro-driver-iosUITests/maestro_driver_iosUITests.swift`
  → method `testHealthKitTapPOC`. Walks snapshot, finds path, resolves on live
  tree, taps, verifies dismissal.
- Workspace-flag negative result: `poc-workspace-config.log` at repo root.

## Footnotes

- iOS 26 simulator behavior reproduced consistently across 6 cycles.
- Demo-app cherry-pick from PR branch verified byte-identical to the PR for
  `HealthAccessManager.swift`, `Runner.entitlements`, `AppDelegate.swift`
  channel handler, `Info.plist` HK keys, and `project.pbxproj` HK additions.
  `main.dart` was manually merged to keep main's `patient_care_screen` while
  adding the HK button.
- Flutter `--no-codesign` strips entitlements from the LD `__entitlements`
  section. Without `flutter build ios --simulator` (with codesign), HKHealthStore
  silently reports unavailable and no auth sheet appears. The POC run script
  uses the codesigned path.
