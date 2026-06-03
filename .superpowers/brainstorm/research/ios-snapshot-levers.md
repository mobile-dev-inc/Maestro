# iOS Snapshot Cost: Pressure-Testing "Snapshot Less Often"

Deep dive for the Maestro rearchitecture. Question under test: **is reducing snapshot
frequency the only lever to cut the ~8s iOS XCUITest snapshot cost, or are there others?**

**Short answer: No.** "Snapshot less often" is one of at least four independent lever
families: (1) snapshot less often, (2) make each snapshot cheaper, (3) replace
poll-and-diff with a native idle signal, (4) bypass the XCUITest snapshot path with a
lower-level AX API, and (5) the gray-box escape hatch (EarlGrey 2) that pays no
cross-process snapshot cost at all. Maestro today uses essentially *none* of the
cheaper-per-snapshot knobs and uses the most expensive possible poll-and-diff loop.

---

## 1. What Maestro does today (confirmed from the repo)

### The snapshot call site
`maestro-ios-xctest-runner/maestro-driver-iosUITests/Routes/Handlers/ViewHierarchyHandler.swift`

- `elementHierarchy(xcuiElement:)` (line 296-299) is the core:
  ```swift
  let snapshotDictionary = try xcuiElement.snapshot().dictionaryRepresentation
  return AXElement(snapshotDictionary)
  ```
  This is `XCUIElement.snapshot()` -> `.dictionaryRepresentation` — the **full,
  attribute-resolved, whole-app accessibility snapshot** serialized to a dictionary. This
  is exactly the operation Appium/WDA documents as "time-expensive."
- `snapshotMaxDepth = 60` (line 10) is a *client-side post-filter / fallback trigger*,
  **not** a request parameter passed into the snapshot. The depth limit is only applied
  to the snapshot request in the fallback/recovery path
  (`AXClientSwizzler.overwriteDefaultParameters["maxDepth"] = snapshotMaxDepth`, lines
  172, 274) — i.e. only after an `kAXErrorIllegalArgument` failure on huge trees. The
  **happy path takes an unbounded-depth snapshot.**
- The snapshot is taken on the **whole foreground app** (`getHierarchyWithFallback(foregroundApp)`),
  plus separate snapshots for status bars, keyboard, alerts, and (iOS 26+) the Safari
  WebView service — multiple full snapshots per "page source" request.

### Snapshot request parameters that ARE tuned
`maestro-driver-iosUITests/Categories/XCAXClient_iOS+FBSnapshotReqParams.m` (ported from
Facebook/WDA) swizzles `defaultParameters`/`snapshotParameters` to set
**`snapshotKeyHonorModalViews = 0`** (so elements behind modals stay visible). The file
header documents the full available parameter set with their XCTest defaults:
```
maxChildren              = INT_MAX
traverseFromParentsToChildren = YES
maxArrayCount            = INT_MAX
snapshotKeyHonorModalViews = NO
maxDepth                 = INT_MAX   <-- not constrained on the happy path
```
So Maestro **has the swizzling machinery to set `maxDepth`/`maxChildren` per-request but
only uses it as an error-recovery fallback, not as a default cost control.**

### Quiescence is explicitly disabled
- `XCUIApplication+Helper.m` and `XCUIApplication+FBQuiescence.m`: on activate,
  `fb_setShouldWaitForQuiescence:NO`. The comment in
  `XCUIApplicationProcess+FBQuiescence.m` says: *"To overwrite default waitForQuiescence
  behavior, swizzle the method here. Currently disabled because **Maestro waits for
  quiescence its own way**."* — i.e. Maestro turned OFF XCUITest's built-in idle wait and
  replaced it with poll-and-diff.

### The poll-and-diff loop (the "its own way")
`maestro-client/src/main/java/maestro/utils/ScreenshotUtils.kt` `waitForAppToSettle`
(lines 38-62) loops:
```kotlin
latestHierarchy = initialHierarchy ?: viewHierarchy(driver)
do {
    val hierarchyAfter = viewHierarchy(driver)        // FULL snapshot every tick
    if (latestHierarchy == hierarchyAfter) { return latestHierarchy }
    latestHierarchy = hierarchyAfter
} while (System.currentTimeMillis() < endTime)
```
Called via `IOSDriver.waitForAppToSettle` (`maestro-client/src/main/java/maestro/drivers/IOSDriver.kt:486-491`),
and `contentDescriptor()` (line 170-177) routes to the same full `iosDevice.viewHierarchy()`.
**Every settle tick is a fresh full-app snapshot**, compared by structural equality. This
is the most expensive possible idle detection: it pays the 8s cost N times to *infer*
idleness that iOS could in principle report directly.

NOTE: A *cheaper* idle primitive already exists in the same file —
`waitUntilScreenIsStatic(timeoutMs, threshold, driver)` (lines 76-91) does a **screenshot
pixel-diff** (`compareImages().differencePercent <= threshold`) rather than a hierarchy
diff. It is not used for the iOS settle path; this is exactly the building block for
lever #6 below.

### Levers Maestro is NOT using
- ❌ Default `maxDepth` cap on the happy-path snapshot request (only in fallback).
- ❌ `maxChildren` cap.
- ❌ Any snapshot caching / reuse across element resolutions within one tick.
- ❌ Attribute filtering / excluding "expensive" custom attributes (visible/accessible).
- ❌ Subtree-scoped snapshots for settle (it always snapshots the whole app).
- ❌ `useFirstMatch` for single-element resolution.
- ❌ Native quiescence/idle signal (explicitly disabled).
- ❌ Lower-level AX bypass (idb-style) for the simulator path.
- ❌ Gray-box in-process mode.

---

## 2. Why the XCUITest snapshot is expensive (what dominates)

From Appium's own "Diagnosing WebDriverAgent Slowness" guide and the WDA source, the cost
has three additive components:

1. **Cross-process XPC round-trip.** The snapshot is requested by the test runner process
   (WDA / Maestro driver) and serviced by `testmanagerd` + the app under test. Each
   snapshot is an inter-process call; the app's main thread must service it. Appium
   explicitly lists "Timed out snapshotting com.apple.testmanagerd" as the symptom.
2. **Full tree traversal + serialization.** WDA: *"to perform XPath lookup / page source,
   WDA needs to take a snapshot of the whole accessibility hierarchy with all element
   attributes resolved."* Cost scales with element count and depth (hundreds of elements
   / React-Native deep trees are the pathological case). This is the part `maxDepth` and
   `maxChildren` attack.
3. **Expensive custom attribute resolution.** WDA computes *visible* and *accessible*
   attributes that are NOT in stock XCTest; their calculation is "expensive in comparison
   to native attributes." Maestro pays the stock `.dictionaryRepresentation` attribute
   set (label, value, frame, type, enabled, etc.) but the principle holds: every attribute
   on every node is fetched and serialized.

Net: cost ≈ (XPC fixed overhead) + O(nodes × attributes traversed). Android's
`uiautomator` dump is cheaper largely because it runs closer to the app and returns a
flatter, lighter tree — hence the <3s vs ~8s gap.

---

## 3. Lever family A — Make each snapshot cheaper (black-box, in-runner)

These are WDA/Appium `FBConfiguration` knobs. Defaults and effects from Appium XCUITest
driver Settings docs and WDA `FBConfiguration.h`:

| Knob | Default | Effect | Est. impact |
|---|---|---|---|
| `snapshotMaxDepth` (request `maxDepth`) | WDA caps at 50; XCTest default INT_MAX | Caps traversal depth; "reasonable 15-100, larger = slower". Bounds the O(depth) term and prevents the `kAXErrorIllegalArgument`/timeout on deep trees | **High** on deep (RN) trees, med otherwise |
| `snapshotMaxChildren` (request `maxChildren`) | INT_MAX | Caps children per node; bounds O(breadth) | Med (high on list-heavy screens) |
| Snapshot caching (`FBConfiguration` snapshot cache) | on in modern WDA | **Reuse ONE snapshot across all element resolutions within a tick**; invalidated after tap/swipe/type. This is the single biggest "less compute per logical operation" win in Appium | **High** for find-heavy flows |
| `customSnapshotTimeout` | removed in XCUITest driver v8; was 15s. Setting to 0 -> WDA computes missing attrs itself, **speeds up page source at the cost of attribute precision** | Tunes the expensive-custom-attribute step | Med |
| `shouldUseCompactResponses` / `elementResponseAttributes` | true / "type,label" | Trims what's serialized in find responses; smaller payload | Low-Med |
| `useFirstMatch` | false | Faster single-element lookup (skips full resolution); known issues with nested lookups | Med (for single-element ops, not page source) |
| `includeNonModalElements` | false | When on, includes more of the tree (cost up). Maestro effectively does the *opposite intent* via `snapshotKeyHonorModalViews=0` | Low |
| `pageSourceExcludedAttributes` / `mobile: source excludedAttributes` | — | Drop "expensive" attrs (visible/accessible) from page source | Med-High when those attrs dominate |
| Subtree scoping | — | Snapshot a specific element/window instead of the whole app | High when only a region matters |

**Takeaway:** Maestro can cut per-snapshot cost meaningfully *without changing frequency*
by (a) setting a default `maxDepth`/`maxChildren` on the happy-path request, (b)
introducing snapshot caching/reuse within a tick, and (c) attribute trimming. None of
these are wired up today.

---

## 4. Lever family B — Native idle instead of poll-and-diff (black-box)

iOS *does* expose idle signals that XCUITest uses internally:

- **`waitForQuiescence` / `automation idle`.** XCUITest waits for the app's run loop to
  quiesce (no pending main-thread work, animations settled, CA transactions committed)
  before/after interactions. Appium surfaces this as `waitForIdleTimeout` (default 10s)
  and `animationCoolOffTimeout` (default 2s); `reduceMotion` shortens animation waits.
- **Reliability caveat:** quiescence is *also the #1 blamed cause of WDA hangs* — apps
  with perpetual animations, spinners, infinite `CADisplayLink`, or background network
  never report idle, so XCUITest blocks until timeout. This is precisely why **Maestro
  disabled it** (`fb_setShouldWaitForQuiescence:NO`) and rolled its own poll-and-diff.

So there IS a native "app is idle" signal — but a naive switch-back would reintroduce the
hangs Maestro escaped. The realistic win is a **hybrid**: use the native idle/animation
cool-off as the *primary* settle signal with a hard timeout, and fall back to a
*single* diff check (or a cheap subtree/screenshot diff) rather than full-snapshot
poll-and-diff on every tick. The key insight: the current loop pays the full snapshot
cost purely to *detect* idleness — that detection can be made far cheaper than a full
page-source snapshot (e.g. screenshot pixel diff, or a depth-capped/region snapshot).

Impact: **High** — replaces N full snapshots per settle with ~1, addressing frequency AND
per-tick cost simultaneously.

---

## 5. Lever family C — Bypass XCUITest snapshot with lower-level AX (black-box-ish)

- **Facebook idb** (`idb ui describe-all`, `describe-point`, `tap`) talks to
  `idb_companion`, which on **simulators** queries the simulator's accessibility bridge
  via CoreSimulator/private AX frameworks **directly — bypassing the XCUITest snapshot
  mechanism**, and is reported to be notably faster than going through `XCUIElement.snapshot()`.
- **Tradeoff / the catch:** this fast path is **simulator-centric**. On *physical
  devices*, AX access must go back through the XCTest/instruments infrastructure (more
  restrictions), so `describe-all`/`describe-point` are reliable mainly on simulators.
  These are also **private/undocumented frameworks**, so they carry the same
  "Apple-can-break-this" risk as Maestro's existing swizzling.
- Underlying AX APIs (`AXClient`/`AXUIElement`, the same family XCTest itself sits on) are
  what both idb and XCTest ultimately use; the win from idb is *skipping the WDA/XCTest
  serialization + custom-attribute layer*, not magic.

Impact: **High on simulator, N/A-to-risky on device.** Good candidate for a
**simulator-only fast lane** (CI runs heavily on simulators), not a universal replacement.

---

## 6. Lever family D — Gray-box (EarlGrey 2): pay no cross-process snapshot cost at all

EarlGrey 2 is built **on top of XCUITest but links a component (the app component) INTO
the app under test**, and uses **eDistantObject (eDO)** for transparent remote method
invocation between the test process and the app process.

Why this eliminates the snapshot problem:

1. **In-process hierarchy read.** Because EarlGrey's matching/interaction code runs
   *inside the app*, it reads the **live `UIView` / `UIAccessibilityElement` hierarchy
   directly** — no XPC snapshot, no full-tree serialization, no custom-attribute
   recomputation. It walks the real object graph in memory.
2. **Real synchronization instead of polling.** EarlGrey's `GREYUIThreadExecutor` drains
   the main run loop and blocks until **both the UI thread and all registered
   `GREYIdlingResource`s are idle** — it automatically tracks UI changes, animations,
   `NSURLSession`/network, dispatch queues, and run-loop sources, and apps can register
   *custom* idling resources. So it gets a true "app is idle" signal and never
   poll-and-diffs snapshots on a timer. (This is the principled version of what Maestro
   approximates with poll-and-diff.)

The cost of this escape hatch:

- **It is gray-box, not black-box.** EarlGrey must be linked into the app (a test target
  built with the app). You cannot point it at an arbitrary installed `.ipa` you don't
  build. That breaks Maestro's core "test any app, no source/SDK changes" value prop.
- Therefore it can only ever be an **optional mode** for teams that build their own app
  and want max speed/stability — not the default path.

Impact: **Highest possible** (eliminates the entire cost class) but **highest integration
cost / narrowest applicability** (requires app instrumentation).

---

## 7. RANKED menu of iOS levers

Tags: **Impact** (High/Med/Low) · **Box** (black-box / gray-box / sim-only) · **Cost** (integration effort).

| # | Lever | Impact | Box | Integration cost | Notes |
|---|---|---|---|---|---|
| 1 | **Native idle + animation cool-off as primary settle, cheap fallback diff** (replace full-snapshot poll-and-diff) | **High** | black-box | Med | Cuts N snapshots/settle to ~1. Must keep a hard timeout to avoid the hangs that made Maestro disable quiescence. Biggest single win that's universal. |
| 2 | **Snapshot caching / reuse within a tick** (one snapshot serves all element resolutions; invalidate on action) | **High** | black-box | Med | Appium's standard win. Maestro currently re-snapshots per logical query. |
| 3 | **Default `maxDepth` (+`maxChildren`) on the happy-path request** | High (deep/RN), Med otherwise | black-box | **Low** | Machinery already exists (used only in fallback). Cap at e.g. 50-60 by default. Risk: deep elements truncated — needs the existing fallback to recover. |
| 4 | **Attribute trimming** (`shouldUseCompactResponses`/`elementResponseAttributes`, drop expensive visible/accessible attrs, `customSnapshotTimeout`-style "compute-if-missing") | Med-High | black-box | Med | Attacks the per-node attribute cost. Tradeoff: less precise visibility attrs. |
| 5 | **Subtree / region-scoped snapshots** for settle and targeted finds | Med-High | black-box | Med | Don't snapshot the whole app when only one screen region matters. |
| 6 | **Cheaper idle detector** (screenshot pixel-diff instead of hierarchy diff for "did anything change") | Med | black-box | Med | Decouples idle detection from page-source cost; pairs with #1. |
| 7 | **`useFirstMatch` for single-element resolution** | Med (single ops) | black-box | Low | Known nested-lookup caveats; scope carefully. |
| 8 | **idb / lower-level AX fast lane on simulators** | High (sim), N/A device | sim-only | Med-High | Bypasses XCUITest snapshot via CoreSimulator AX bridge. Private framework risk; device path unreliable. Great for CI sim runs. |
| 9 | **Reduce settle frequency / smarter when-to-snapshot** ("snapshot less often" — the prior thesis) | Med | black-box | Low-Med | Real but partial; orthogonal to and weaker than #1-#4. |
| 10 | **Gray-box mode (EarlGrey-2-style in-process)** | **Highest** (eliminates cost class) | gray-box | **High** | Requires linking into the app; breaks black-box "any app" promise. Optional opt-in only. |

---

## 8. Direct answer + recommendation for Maestro's iOS strategy

**Is "snapshot less often" the only solve? No.** It is the *weakest and most partial* of
the available levers. The dominant cost is (a) cross-process XPC + (b) full-tree,
all-attribute traversal/serialization, paid (c) once per settle *tick* in a poll-and-diff
loop. Each of those three is independently attackable:

- (a) is dodged entirely only by going gray-box / in-process (EarlGrey-style), or partly
  by idb's lower-level AX on simulators.
- (b) is cut by per-snapshot knobs Maestro already has the machinery for but doesn't use
  by default (`maxDepth`/`maxChildren`, attribute trimming, subtree scoping, snapshot
  reuse).
- (c) is cut by replacing poll-and-diff with a native idle signal + cheap fallback —
  which reduces *both* frequency and the cost-per-detection.

### Recommended default (black-box) path — ordered, do these:
1. **Stop disabling quiescence outright; adopt a hybrid settle:** native idle +
   animation cool-off as the primary signal, capped by a hard timeout, with a *single*
   cheap diff (screenshot or depth-capped subtree) as the fallback. This kills the
   "N full snapshots per settle" pattern — the root cost multiplier.
2. **Add snapshot reuse within a tick** so all element resolutions for one logical step
   share one snapshot (invalidate on tap/swipe/type).
3. **Set a default `maxDepth` (and `maxChildren`) on the happy-path snapshot request**
   (the swizzle already supports it), keeping the existing fallback for deep trees.
4. **Trim attributes / scope snapshots to subtrees** where the operation allows.

### Reserve for an optional mode:
- **Simulator fast lane via idb / lower-level AX** for CI-heavy simulator runs (opt-in,
  feature-flagged, with the XCUITest path as the device fallback).
- **Gray-box (EarlGrey-2-style in-process) mode** for teams that build their own app and
  want to eliminate the cross-process snapshot cost class entirely and get real
  synchronization. This is the ceiling on performance/stability but costs the black-box
  "any app, no instrumentation" promise, so it must stay an explicit opt-in, not the
  default.

The combination of (1)+(2)+(3) is achievable inside the existing
`maestro-ios-xctest-runner` + `ScreenshotUtils` architecture with no app instrumentation,
and should close most of the iOS/Android gap before any gray-box investment.

---

## Sources

- Appium XCUITest Driver — Settings: https://appium.github.io/appium-xcuitest-driver/latest/reference/settings/
- Appium XCUITest Driver settings.md (master): https://github.com/appium/appium-xcuitest-driver/blob/master/docs/reference/settings.md
- Diagnosing WebDriverAgent Slowness: https://appium.github.io/appium-xcuitest-driver/latest/guides/wda-slowness/
- Appium issue #14825 (deep RN trees / snapshotMaxDepth): https://github.com/appium/appium/issues/14825
- Appium issue #18085 / #19661 (snapshotMaxDepth behavior): https://github.com/appium/appium/issues/18085
- WDA FBConfiguration.h: https://github.com/appium/WebDriverAgent/blob/master/WebDriverAgentLib/Utilities/FBConfiguration.h
- WDA XCUIElement+FBWebDriverAttributes.m (custom visible/accessible attrs): https://github.com/appium/WebDriverAgent/blob/master/WebDriverAgentLib/Categories/XCUIElement+FBWebDriverAttributes.m
- WDA FBElementCommands.m (page source / element cache): https://github.com/appium/WebDriverAgent/blob/master/WebDriverAgentLib/Commands/FBElementCommands.m
- idb (facebook): https://github.com/facebook/idb  and  https://www.fbidb.io/docs/commands/
- Building an iOS Accessibility Inspector using idb: https://medium.com/@adityabhardwaj/building-an-ios-accessibility-inspector-using-idb
- EarlGrey 2.0 README (eDistantObject / gray-box): https://chromium.googlesource.com/external/github.com/google/EarlGrey/+/8317e42e21520939e20e972a0cade2925680d870/README.md
- EarlGrey GREYUIThreadExecutor: https://google.github.io/EarlGrey/Classes/GREYUIThreadExecutor.html  and  source: https://chromium.googlesource.com/external/github.com/google/EarlGrey/+/refs/heads/master/EarlGrey/Synchronization/GREYUIThreadExecutor.m
- EarlGrey idling resources (issue #505): https://github.com/google/EarlGrey/issues/505
- Appium vs EarlGrey (black-box vs gray-box): https://www.qualiti.ai/blog/whats-the-diff-appium-vs-earlgrey

### Maestro repo evidence
- `maestro-ios-xctest-runner/maestro-driver-iosUITests/Routes/Handlers/ViewHierarchyHandler.swift` (snapshot call site; `snapshotMaxDepth=60` post-filter; maxDepth only in fallback)
- `maestro-ios-xctest-runner/maestro-driver-iosUITests/Categories/XCAXClient_iOS+FBSnapshotReqParams.m` (snapshot-param swizzling; only sets `snapshotKeyHonorModalViews=0`)
- `maestro-ios-xctest-runner/maestro-driver-iosUITests/Categories/XCUIApplication+FBQuiescence.m`, `XCUIApplicationProcess+FBQuiescence.m`, `XCUIApplication+Helper.m` (quiescence explicitly disabled)
- `maestro-client/src/main/java/maestro/utils/ScreenshotUtils.kt` `waitForAppToSettle` (full-snapshot poll-and-diff loop, lines 38-62); `waitUntilScreenIsStatic` (screenshot pixel-diff, lines 76-91 — exists but unused for iOS settle)
- `maestro-client/src/main/java/maestro/drivers/IOSDriver.kt` (`contentDescriptor` line 170, `viewHierarchy` line 176, `waitForAppToSettle` lines 486-491)
