# Observation Layer Review — Deep Architecture Review for the Maestro Rearchitecture

> Scope: the OBSERVATION dimension only (UI hierarchy + selectors + "what can we see").
> Reviews the proposed typed cross-platform `Element` model, cheap `observe(query)` /
> rare `snapshot()`, merged-by-default, and the one-line-per-element LLM projection from
> `worked-example-core-contract.md`.
>
> This builds on `research/observation-hierarchy.md` and `research/ios-snapshot-levers.md`.
> It does **not** repeat their framework surveys; it goes deeper into Maestro's actual
> code paths (iOS and Android **separately**), the PR rationale behind each load-bearing
> choice, a precise field-level iOS-vs-Android "what we can see" table, and a feasibility
> verdict for a single consolidated hierarchy.
>
> Structure: **(A)** what Maestro has today, **(B)** what every other framework does,
> **(C)** the recommendation.

---

## A. WHAT MAESTRO HAS TODAY

### A.0 The core data model (shared, untyped)

`maestro-client/.../maestro/TreeNode.kt` — the **single** in-memory node, identical for
all platforms:

```kotlin
data class TreeNode(
    val attributes: MutableMap<String, String> = mutableMapOf(),  // untyped string bag
    val children: List<TreeNode> = emptyList(),
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
) { fun aggregate(): List<TreeNode> = listOf(this) + children.flatMap { it.aggregate() } }
```

Critical facts confirmed from code:

- **There is no `role` anywhere in the matching engine.** I grepped the entire
  `maestro-client/src/main`: the only `role`-ish strings are in `MockInteractor.kt`
  (unrelated). Selection is by `text` / `accessibilityText` / `hintText` / `resource-id` /
  bounds / the five booleans. **The proposal's ARIA role enum is 100% greenfield** — it is
  not a refactor of an existing field, it's a new capability that must be *synthesized* on
  every platform (from `elementType` on iOS, `class` on Android, `tagName`+ARIA on web).
- `Bounds` is `typealias Bounds = String` (in `Bounds.kt` it's actually a `data class`, but
  the iOS/Android attribute is a *string* like `[x1,y1][x2,y2]` parsed on demand in
  `UiElement.toUiElementOrNull()`). The proposal's typed `Rect` is a real improvement.
- The five typed booleans (`clickable/enabled/focused/checked/selected`) are the only
  promoted fields. Everything else is stringly-typed.
- `aggregate()` flattens the whole tree to a flat `List<TreeNode>`. **The entire selector
  language runs over this flat list** (`Filters.kt`), which is why a full tree is needed
  (see A.5).

### A.1 iOS path — precise field capture

**Wire:** `IOSDriver.contentDescriptor()` → `iosDevice.viewHierarchy()` →
HTTP to the on-device XCTest runner → `ViewHierarchyHandler.swift`.

**The capture (device side, `ViewHierarchyHandler.swift`):**
```swift
let snapshotDictionary = try xcuiElement.snapshot().dictionaryRepresentation  // line 297
return AXElement(snapshotDictionary)
```
- `XCUIElement.snapshot().dictionaryRepresentation` on the **foreground app** = a full,
  attribute-resolved, whole-app accessibility snapshot. This is the exact operation Appium
  documents as the >8s pathological case.
- **The happy path takes an UNBOUNDED-depth snapshot.** `snapshotMaxDepth = 60` is a
  *post-filter* used only to decide whether to recurse, and `maxDepth` is only pushed into
  the snapshot request (`AXClientSwizzler.overwriteDefaultParameters["maxDepth"]`) in the
  `kAXErrorIllegalArgument` **recovery** path, not by default.
- It snapshots **multiple surfaces** per request: foreground app + status bars
  (springboard) + keyboard + alerts + custom window + (iOS 26+) the
  `com.apple.SafariViewService` WebView process. So "one snapshot" is actually several.
- Window-offset math (`expandElementSizes`) re-bases coordinates because springboard
  reports portrait dimensions even in landscape — a fragile coordinate-space hack.

**The fields actually captured** (`AXElement.swift` / `AXElement.kt`):
`identifier`, `frame` (X/Y/W/H), `value`, `title`, `label`, `elementType` (Int),
`enabled`, `placeholderValue`, `selected`, `hasFocus`, `horizontalSizeClass`,
`verticalSizeClass`, `displayID`, `windowContextID`, `children`.

**The mapping to `TreeNode` (`IOSDriver.mapViewHierarchy`):**
```
accessibilityText = label            // the accname-ish field
title             = title
value             = value
text              = title.ifEmpty { value }   // <-- text is title OR value
hintText          = placeholderValue
resource-id       = identifier       // accessibilityIdentifier
bounds            = frame.boundsString
enabled/focused/selected = passthrough
checked = (elementType in {checkbox=12, switch=40, toggle=41}) && value == "1"  // DERIVED
```

**What iOS does NOT capture / derive — load-bearing gaps:**
- **`clickable` is never set on iOS** (`TreeNode.clickable` is always null). So
  `Filters.clickableFirst()` is a no-op on iOS. Tap-target disambiguation that works on
  Android silently degrades on iOS.
- **`scrollable` is never captured on iOS** — it's an Android-only attribute. iOS scroll
  targeting cannot key off a `scrollable` flag.
- **`isHittable` is captured by XCUITest but THROWN AWAY.** `XCUIElementSnapshot` exposes
  `isHittable` (real hit-test) but `AXElement` doesn't include it. Maestro instead computes
  visibility *geometrically* in Kotlin (`ViewHierarchy.isVisible` does a center-point
  hit-test against the flattened tree). **This is the single most important asymmetry for
  the proposal's `hittable` field** (see C.4): iOS would have to either start surfacing
  `isHittable` (cheap, already computed) or keep the geometric approximation.
- No `checkable`, `password`/secure, `focusable`, `long-clickable`,
  `important-for-accessibility`, `error`, `visible-to-user` — all of which Android *does*
  capture (A.2).

### A.2 Android path — precise field capture

**Wire:** `AndroidDriver.contentDescriptor()` → **gRPC** to the on-device instrumentation
app (`dev.mobile.maestro`) → `ViewHierarchy.dump()` (device side) returns **XML** →
client parses XML in `mapHierarchy()` → CDP WebView augmentation → keyboard pruning.

**The capture (device side, `maestro-android/.../ViewHierarchy.kt`):**
- It is a **modified copy of UiAutomator's `AccessibilityNodeInfoDumper`**. It walks
  `AccessibilityNodeInfo` directly.
- It uses **`device.getWindowRoots()` via reflection** to capture **ALL windows**, not just
  `rootInActiveWindow` — so system dialogs, IME, toasts, overlays across app boundaries are
  all included. (Falls back to `uiAutomation.rootInActiveWindow` if reflection fails.)
- It only descends into children where `isVisibleToUser` is true (with a documented WebView
  workaround: `child.isVisibleToUser || insideWebView`, because Android reports WebView
  contents as invisible — this is PR #169's fix).
- It computes `NAF` ("Not Accessibility Friendly") flags and visible-bounds-clipped-to-
  screen rects.

**Per-node XML attributes emitted:** `index`, `hintText`, `text`, `resource-id`, `class`,
`package`, `content-desc`, `checkable`, `checked`, `clickable`, `enabled`, `focusable`,
`focused`, `scrollable`, `long-clickable`, `password`, `selected`, `visible-to-user`,
`important-for-accessibility`, `error`, `bounds`, plus `NAF`.

**The mapping to `TreeNode` (`AndroidDriver.mapHierarchy`)** keeps a *subset*:
`text`, `accessibilityText`(←content-desc), `hintText`, `resource-id`, `clickable`,
`bounds`, `enabled`, `focused`, `checked`, `scrollable`, `selected`, `class`,
`important-for-accessibility`, `error`, plus `ignoreBoundsFiltering` (set for Toasts).
**Dropped at the client mapping step:** `checkable`, `focusable`, `long-clickable`,
`password`, `visible-to-user`, `package`, `NAF`, `index`. (They're in the XML but not lifted
into attributes.) Note: `password`/secure is captured device-side but **discarded** —
relevant to C.4's `secure` state.

**Android key asymmetries vs iOS:** `clickable`, `scrollable`, `checkable`,
`important-for-accessibility`, `error`, `visible-to-user` exist on Android and have **no iOS
equivalent in the captured data**. iOS `title`+`label`+`placeholderValue` split is richer
than Android's `text`/`content-desc`/`hintText` but maps roughly 1:1.

### A.3 WebView / CDP augmentation (Android only, opt-in)

`AndroidWebViewHierarchyClient.augmentHierarchy()` (added/reworked in PRs #2372, #2414):
- Only runs when `chromeDevToolsEnabled` AND the base tree contains an
  `android.webkit.WebView` node.
- Pulls the WebView DOM tree over **Chrome DevTools Protocol** (`DadbChromeDevToolsClient`),
  then **merges** DOM nodes into the accessibility tree by **geometric intersection + text/id
  substring matching** (`mergeWith`). New web nodes that don't match an existing AX node are
  appended as siblings of the root.
- **Why it exists:** Android's `AccessibilityNodeInfo` exposes WebView content poorly/
  unreliably; the DOM has the real text/ids. This is a concrete instance of "the
  accessibility tree is lossy and a second source (DOM) must be fused in."
- **iOS does the analogous thing differently:** there is no CDP merge; instead iOS 26+
  reaches into the separate `SafariViewService` process and snapshots its WebView AX tree
  (PR #2872). For in-app `WKWebView`, iOS relies on XCUITest surfacing web AX elements
  natively.
- **Lesson for the proposal:** WebView is a first-class `frameworkSource` that requires a
  *separate capture path and a fusion step* on both platforms. The single `observe(query)`
  primitive must transparently span native + WebView, and the merge is non-trivial
  (intersection + substring today — fragile).

### A.4 Settle / equality / `FAST_HIERARCHY` — the biggest hidden divergence

`waitForAppToSettle` is **fundamentally different per platform**, gated by the
`Capability.FAST_HIERARCHY` flag — **declared by Android only** (`AndroidDriver.capabilities()`),
**not iOS, not web**.

- **Android (`FAST_HIERARCHY`):** `hierarchyBasedTap` — dump full hierarchy, tap,
  `waitForWindowToSettle` polls `isWindowUpdating` (a cheap device-side AccessibilityEvent
  signal) and diffs full hierarchies; tap is "done" when the hierarchy changed.
- **iOS (no `FAST_HIERARCHY`):** `screenshotBasedTap` — takes a **screenshot pixel-diff**
  first (`waitUntilScreenIsStatic`, threshold 0.005) and only falls back to a full snapshot
  diff if the screenshot didn't change. This was deliberately introduced in **PR #787**
  ("Remove hierarchy request for waitForAppToSettle (iOS)") because the iOS snapshot is so
  expensive that screenshot diffing is cheaper. `SCREEN_SETTLE_TIMEOUT_MS` was raised
  because screenshot diff became the primary settle source.
- The generic `ScreenshotUtils.waitForAppToSettle` (the full-snapshot poll-and-diff loop,
  with an `is-loading` web hook) is the *fallback* both platforms share.

**Implication for the proposal:** the proposal's event-driven `waitForIdle` / `device.idle`
push (Trace dimension, `s3` in the worked example) **does not exist today on either
platform**. Android approximates it with `isWindowUpdating`; iOS approximates it with
screenshot pixel-diff. There is **no native idle event** wired up. The proposal's elegant
"actionability transition pushed by device.idle" is aspirational and is the part of the
contract with the least existing substrate. (See `ios-snapshot-levers.md` §4: iOS *has*
`waitForQuiescence` but Maestro **disabled** it — PR #2332 — because Apple's idle wait hung
60s+ on animated screens. So even the native signal that exists was rejected as unreliable.)

### A.5 Selector engine — why a full flat tree is load-bearing

`Filters.kt` runs entirely over `aggregate()` (the flat list). The following selectors are
**whole-tree operations** that a targeted `observe(role+name)` *cannot* answer without a
tree:
- `below` / `above` / `leftOf` / `rightOf` (`relativeTo` — needs all candidates + geometry)
- `containsChild` / `containsDescendants` (structural)
- `index(n)` (sorts ALL matches by y,x — needs the full match set)
- `clickableFirst` (sorts by `clickable` — and is already a no-op on iOS, A.1)
- `css(...)` → `findElementsByOnDeviceQuery` (the ONLY targeted-query path, web-only)

**The prior art for `observe(query)` already exists but is web-only:**
`Driver.queryOnDeviceElements(query: OnDeviceElementQuery)` has a default no-op
implementation and is **only** overridden by `CdpWebDriver` (CSS selector → `querySelectorAll`
in `maestro-web.js`). **iOS and Android have no targeted on-device query at all** — there is
no element-query route in the iOS XCTest runner (only `ViewHierarchyHandler`), and Android's
gRPC has no `findElements` RPC. So the proposal's "cheap targeted `observe`" requires
**building a new device-side query engine on both iOS and Android** that does not exist
today. On iOS, per `ios-snapshot-levers.md` §3, even a "targeted" XCUITest query frequently
still materializes a scoped snapshot — so the iOS win is far smaller than on Android.

### A.6 PR archaeology — the load-bearing choices, with rationale and current validity

| Choice | PR/commit | Why added | Still valid? |
|---|---|---|---|
| **Self-dump Android AX tree via `getWindowRoots()`** (don't use `uiautomator dump`) | #169 (`0b0035dc`) | UiAutomator's built-in dump reported WebView contents as invisible; needed all-windows + WebView workaround | **Yes.** Cross-app/all-windows capture is exactly the black-box value prop. Keep. |
| **iOS settle via screenshot-diff, not hierarchy (`FAST_HIERARCHY`)** | #787 (`1aa153c1`) | iOS snapshot too slow; screenshot pixel-diff is cheaper for RN/complex trees | **Partially.** Right that snapshot is too costly to poll. But it's a workaround for a missing native idle signal; the proposal should replace with event-driven idle, not bless the pixel-diff forever. |
| **iOS `/viewHierarchy` with depth-bounded recursive fallback** (replaces idb fallback) | #1209 (`70e7ab30`) | idb's tree was a different shape and *missed elements* (e.g. TabBar items). XCTest snapshot errors `kAXErrorIllegalArgument` on big RN trees | **Yes, and instructive:** idb (the "fast lane" in `ios-snapshot-levers.md` §5) was tried and **rejected for correctness** — it loses elements. Any "faster AX bypass" proposal must clear this bar. |
| **Depth insight warning (`WARNING_MAX_DEPTH=61`)** | #1274 (`b9c327ad`) | Warn users when deep trees (RN) slow snapshots; nudge to RN new-arch view flattening | **Yes** as UX, but it's a symptom of the full-snapshot cost. The targeted-observe direction attacks the root cause. |
| **Disable XCUITest quiescence entirely** | #2332 (`443fdfc4`) | Apple's `waitForQuiescence` hung 60s+ on animated screens (scroll/swipe), causing timeouts | **Yes** — and a direct warning to the proposal: the "native idle event" path is exactly what Apple provides and what Maestro found unreliable. A hybrid with a hard timeout is mandatory. |
| **Android WebView via CDP merge** | #2372/#2414 | `AccessibilityNodeInfo` exposes WebView text/ids poorly; DOM has the truth | **Yes**, but the merge heuristic (geometric intersection + substring) is fragile and opt-in (`chromeDevToolsEnabled`). |
| **iOS 26 Safari WebView via SafariViewService** | #2872 (`c932ff14`) | `SFSafariViewController` moved to a separate process; host-app snapshot missed all web elements | **Yes** — and shows the capture surface keeps fragmenting (separate processes). The proposal's single `observe` must keep absorbing these. |

**Net archaeology takeaway:** every "full tree" decision was made for a *correctness* reason
(cross-app capture, WebView visibility, RN completeness, missing-element avoidance), and
every "make it cheaper" decision (screenshot-diff settle, idb fallback) was either a
workaround or **rejected for losing elements**. The proposal is right to demote the full dump
but must treat *completeness* and *cross-app capture* as the bar idb already failed to clear.

---

## B. WHAT EVERY OTHER FRAMEWORK DOES (DOM shape + most-robust selector)

The prior research covered Appium, XCUITest queries, Compose semantics, SwiftUI a11y, RN,
Playwright ARIA, W3C computed-role, Flipper/UI-Debugger, Expo, Android Studio Layout
Inspector. Below is the **delta** — frameworks/details that research under-covered, plus a
consolidated selector-precedence synthesis.

| Framework | "DOM" shape | Query/selector model | Most-robust selector (their own guidance) |
|---|---|---|---|
| **Appium** | Page-source **XML** of the native AX tree (XCUITest snapshot / UiAutomator nodes) | `findElement` by id / `-ios predicate string` / `-ios class chain` / `By` / XPath (slow) | **accessibility id** > predicate/class-chain > XPath. XPath triggers full snapshot — avoid. |
| **XCUITest** | Lazy `XCUIElementQuery` over `XCUIElementSnapshot` (elementType, identifier, label, title, value, placeholderValue, frame, isEnabled, isSelected, **isHittable**) | `app.buttons["id"]`, `element(boundBy:)`, `NSPredicate` | **accessibilityIdentifier** > label. Two name fields (label+title) + value + placeholder. |
| **Espresso** | In-process Android view tree (gray-box) | Hamcrest matchers: `withId`, `withText`, `withContentDescription`, `withTagValue` | `withId` (R.id) > content-desc > text. |
| **EarlGrey 2** | In-process iOS view/AX tree (gray-box, eDistantObject) | `grey_accessibilityID`, `grey_accessibilityLabel`, `grey_accessibilityTrait`, `grey_sufficientlyVisible` | **`grey_accessibilityID` first** (uniqueness), then label; order matchers most-selective-first. **AX=N (non-accessible) elements can't be matched by any AX matcher** — must use non-AX matchers. (Mirror of RN `accessible={true}` flattening.) |
| **Detox** | Native view tree (gray-box, on RN) | `by.id`(testID) / `by.text` / `by.label` / `by.type` / `by.traits` | **`by.id` (testID) is explicitly the recommended primary.** Locale-agnostic, least likely to change. Warns testID must be *forwarded* to a real native view or the matcher fails — same view-tag footgun research flagged. |
| **Flutter** | **Semantics tree** (not widget tree); `flutter_driver` `SerializableFinder` | `find.byValueKey` / `find.bySemanticsLabel` / `find.byText` / `find.byType`; finders are **JSON-serializable** (sent to device, resolved there — true targeted query) | **`ByValueKey`** (explicit `Key`) > `bySemanticsLabel`. Note: serializable finder = device-side resolution, exactly the `observe(query)` model. |
| **WebdriverIO** | Web DOM / native AX tree (Appium under the hood for mobile) | CSS / `getComputedRole` / `getComputedLabel` / accessibility selectors | role+name (computed) and a11y id; aligns with W3C computed-role endpoints. |
| **mobile-mcp** | **Flat list** of elements (type, text, label, coordinates) from the AX tree, OR coordinate taps from a screenshot | `list_elements_on_screen` → structured JSON; accessibility-locator preferred, coordinates fallback | accessibility label/id preferred; coordinates when missing. **This is essentially the proposal's flat projection already shipping in an AI agent context.** |
| **Playwright (ARIA)** | `ariaSnapshot` = YAML of the a11y tree keyed by **role + accessible name** + select states; **`[ref=eN]`** handles | Locators by role/name/text/testid; refs for AI | role+name > testid > text. **Refs are stable within a snapshot, regenerated when the page changes** — exactly the proposal's `ref` semantics. |

**Cross-framework convergence (the strong signal):** the *modern* tool-facing identity is
**stable id/testID/key → role + accessible name → visible text → coordinates**, derived from
the **accessibility/semantics tree, not the render tree**, queried **lazily/targeted**, with
the full tree kept for debugging. Gray-box tools (Espresso, EarlGrey, Detox, Flutter
driver) get this in-process and cheaply; black-box tools (Appium, Maestro, mobile-mcp) pay a
snapshot cost and therefore *must* push queries device-side and keep the full dump rare.

### B.1 Most-robust selector precedence (synthesized recommendation)

Every framework that published guidance agrees on this ordering. Recommended precedence for
Maestro's resolver:

1. **Stable developer-assigned id** — `nativeId` = accessibilityIdentifier (iOS) /
   resource-id (Android) / testID/Key (RN/Flutter, when surfaced) / `id`/`data-testid`
   (web). Most stable, locale-agnostic. **Caveat:** RN `testID` lands in Android `view-tag`,
   which `AccessibilityNodeInfo` does NOT expose — so it is *invisible to Maestro's
   black-box dump* unless the dev also sets `accessibilityLabel`/content-desc. This is a real
   hole: Maestro will silently fail to match many RN `testID`s on Android.
2. **role + accessible name** — the cross-platform semantic identity (ARIA-aligned). Stable
   across refactors, human-readable, what the LLM projection uses.
3. **visible text** — fragile to copy/locale changes but high recall.
4. **coordinates / vision** — last resort; always re-ground on fresh bounds before acting.

Maestro today has **no role tier** (tier 2 doesn't exist) and a *weak* tier 1 (RN view-tag
hole). The proposal correctly elevates tiers 1–2; the resolver must encode this precedence
explicitly with `nth`/scoring as tiebreak.

---

## C. RECOMMENDATION & VERDICT

### C.1 Endorse the direction; it matches the entire industry

Typed `Element` + ARIA role enum + targeted `observe()` + rare depth-bounded `snapshot()` +
Playwright-style ref'd projection is exactly where Appium (tuned, not removed page-source),
Playwright, Compose semantics, Flutter serializable finders, mobile-mcp, and W3C computed-
role have converged. Replacing the untyped `attributes: Map<String,String>` bag on a
full-dump-by-default is correct. **Keep it.**

### C.2 Precise iOS-vs-Android "WHAT WE CAN SEE" table (field level)

Legend: ✅ captured & mapped today · ⚠️ obtainable but not currently captured/mapped ·
❌ not available from the platform's black-box source · derived = computed by Maestro.

| Proposed `Element` field | iOS (XCUITest snapshot) | Android (`AccessibilityNodeInfo`) | Notes / divergence |
|---|---|---|---|
| `role` (ARIA enum) | ⚠️ derive from `elementType:Int` (XCUIElementType ~80 values) | ⚠️ derive from `class` string (+ Compose `Role` if unmerged) | **Neither captured today.** Mapping tables differ in granularity; iOS elementType is an enum, Android is an open class string. Hardest field to unify. |
| `rawRole` | ✅ `elementType` | ✅ `class` | Keep both; escape hatch. |
| `name` (accname) | ✅ `label` | ✅ `content-desc` (→accessibilityText) | iOS also has `title` (✅) — **two name-ish fields** collapse poorly to one. |
| `text` (visible) | ✅ `title ?: value` (overloaded) | ✅ `text` | iOS `text` is title-OR-value, not true visible text. |
| `value` | ✅ `value` | ⚠️ no distinct value; often in `text` | Android lacks a clean value/text split. |
| `placeholder` | ✅ `placeholderValue` | ✅ `hintText` (API 26+) | Roughly 1:1. |
| `nativeId` | ✅ `identifier` | ✅ `resource-id` | RN `testID`→Android view-tag is **invisible** here (B.1). |
| `state.enabled` | ✅ `enabled` | ✅ `enabled` | OK. |
| `state.focused` | ✅ `hasFocus` | ✅ `focused` | OK. |
| `state.selected` | ✅ `selected` | ✅ `selected` | OK. |
| `state.checked` (tri-state) | derived (elementType∈{12,40,41} && value=="1") — **boolean only** | ✅ `checked` (+`checkable` ⚠️ discarded) | Neither captures `indeterminate`. iOS checked is a heuristic. |
| `state.editable` | ⚠️ infer from elementType (textField/secureTextField) | ⚠️ infer from `class` (EditText) | Not captured today. |
| `state.secure` (password) | ⚠️ infer from elementType (secureTextField=49) | ⚠️ `password` captured device-side but **dropped at mapping** | Needed for redaction; trivial to surface on Android. |
| `state.expanded` | ❌ no snapshot field | ⚠️ via `AccessibilityNodeInfo` actions (not dumped) | Weak on both. |
| `state.busy` | ❌ | ⚠️ not dumped | No settle substrate from state. |
| `scrollable` | ❌ **never captured** | ✅ `scrollable` | **Asymmetric.** iOS has no scrollable flag in the dump. |
| `clickable` | ❌ **never set** (always null) | ✅ `clickable` | **Asymmetric.** `clickableFirst` is a no-op on iOS. |
| `bounds` (Rect) | ✅ `frame` (points; needs window-offset correction) | ✅ `bounds` (pixels, clipped to screen) | **Different coordinate spaces** (points vs px). Classic bug. |
| `visible` | derived (geometric center hit-test in Kotlin) | ✅ `visible-to-user` (⚠️ dropped at mapping; descent already filters) | iOS has no `visible` bit; Android has one but it's filtered at dump time. |
| `hittable` | ⚠️ **XCUITest `isHittable` exists but is discarded** | ❌ no direct occlusion bit (geometric only) | **The proposal's `hittable` is the riskiest field** (C.4). |
| `engineRef` (stable handle) | ❌ no stable node id across snapshots | ❌ same | Must be synthesized (path/hash) — see C.5. |
| structure (childCount/parent) | ✅ via tree | ✅ via tree | Only available from a *tree*, not a flat targeted query. |

**Where the platforms fundamentally differ:**
- **iOS gives a richer name model** (label+title+value+placeholder + an elementType enum) but
  **no interaction-affordance bits** (clickable/scrollable/checkable/hittable in the dump).
- **Android gives richer affordance bits** (clickable/scrollable/checkable/long-clickable/
  focusable/password/visible-to-user) but **a weaker name/value split** (text/content-desc/
  hint) and an open-ended `class` instead of a role enum.
- **iOS has no native "what changed" cheap signal** (hence screenshot-diff); **Android has
  `isWindowUpdating`** (an AccessibilityEvent-derived signal). Settle is structurally
  different and the proposal's event-driven idle exists on *neither* yet.
- **Compose changes the Android story entirely:** `AccessibilityNodeInfo` exposes the
  **merged** semantics tree; the *unmerged* tree (individual children, `TestTag`,
  `ToggleableState`, `Role`) is **only** reachable in-process via Compose testing APIs, which
  black-box Maestro cannot use. So Maestro on Compose sees the merged a11y projection — it
  **cannot** offer "unmerged opt-in" for Compose without gray-box instrumentation. This
  directly contradicts the proposal's "unmerged opt-in" as a universal capability.

### C.3 Merged-vs-unmerged: the proposal's default is right but not universally honorable

Prior research correctly flags this as the sharpest correctness gap. Going further with the
code reality:
- **iOS**: XCUITest already returns a *merged-ish* accessibility tree (SwiftUI/UIKit merge
  accessible children). Maestro gets merged by default and **cannot easily get unmerged**
  black-box.
- **Android classic Views**: the dump is the merged a11y tree; `important-for-accessibility`
  is captured and could drive merge decisions, but Maestro does no merging itself.
- **Android Compose**: merged-only via black-box (C.2). Unmerged needs gray-box.
- **Verdict:** ship **merged by default** (matches all three black-box realities and what
  users tap). But **"unmerged opt-in" must be documented as best-effort / platform-limited**,
  NOT a guaranteed cross-platform capability. On Compose it is effectively unavailable
  black-box. Promising universal unmerged will create silent iOS/Android/Compose divergence.

### C.4 `hittable` is the highest-risk field — pin its definition NOW

The proposal treats `hittable` as a first-class, Playwright-style actionability bit and even
drives the `wait` span off it (`device.idle` → `hittable:true`). But today:
- iOS **discards** the one real hit-test (`XCUIElementSnapshot.isHittable`).
- Android **has no occlusion bit at all**; Maestro approximates via geometric center
  hit-test against the flattened tree (`ViewHierarchy.isVisible`).

So "hittable" means three different things depending on platform unless pinned. Recommend:
1. **iOS: start surfacing `isHittable`** from the snapshot (it's already computed; just add
   it to `AXElement`). Cheap, removes a derived approximation.
2. **Android: keep the geometric hit-test** but document it as "not occluded per the AX
   tree's geometry" — it cannot detect a non-AX overlay (e.g., a SurfaceView/video covering
   a button). Pair with vision for occlusion-sensitive asserts.
3. Define `hittable` precisely in the contract: *visible AND enabled AND the center point
   resolves to this element (or a descendant) in the current tree*. Make the divergence
   explicit, not silent.

### C.5 `engineRef` / staleness — copy Playwright's exact semantics

Neither platform exposes a stable node identity across snapshots. The proposal's `ref` must
be **synthesized and scoped to one observation generation** (like Playwright's `[ref=eN]`:
stable within a snapshot, invalidated when the screen changes). Maestro today re-finds nodes
by **structural attribute equality** (`ViewHierarchy.refreshElement` matches on
`attributes - "bounds"`, requiring exactly one match) — that's the existing re-resolution
mechanism and it's brittle (fails if 0 or >1 match). Recommendation: the contract must state
that a `ref` is only valid until the next observation, and `tapOn({ref})` **re-resolves the
underlying selector** (not raw cached bounds) before acting. Bake the re-resolve-before-act
rule into the contract (research §2.6 flagged this; the code confirms `refreshElement` is the
current — fragile — answer).

### C.6 Feasibility of a single consolidated cross-platform hierarchy view

**Verdict: feasible for the IDENTITY layer, NOT feasible as a lossless universal tree.**

A consolidated `Element` keyed on **role + name + value + placeholder + nativeId + bounds +
{enabled,focused,selected,checked} + visible** is genuinely achievable across iOS, Android
classic, RN, and (merged) Compose/SwiftUI — these are precisely the fields both platforms can
produce or derive, and it's what every other black-box tool ships. **This is the right
abstraction and the proposal should commit to it.**

It is **NOT** feasible to consolidate:
- **Affordance bits** (`clickable`/`scrollable`/`checkable`) — iOS simply doesn't provide
  them in the dump. Model them as `optional`/`tri-state-unknown`, never assume false-on-iOS.
- **Unmerged structure on Compose** (C.3) — black-box can't reach it.
- **`hittable` semantics** (C.4) — must be a per-platform-defined approximation.
- **Canvas / games / Flutter-without-semantics / RN `accessible={true}`-flattened subtrees /
  non-accessible (AX=N) elements** — these produce sparse or empty AX trees on *every*
  framework (EarlGrey explicitly can't match AX=N; RN flattening hides children; Flutter
  needs semantics enabled). A role-keyed model degrades to "one node or nothing" here.
  **Vision is a mandatory third leg**, not optional. The proposal's projection should be able
  to fall back to a screenshot + coordinate target, and the `Element` needs a
  `frameworkSource`/`kind` tag (`native|compose|swiftui|webview|flutter|canvas|unknown`) so
  the orchestrator knows when to switch to vision.

**Therefore:** consolidate the *identity*, keep an explicit typed `extra`/`raw` escape hatch
for platform-specific fields (Flipper's proven choice), mark affordance/structure/hittable as
explicitly-optional-and-platform-defined, and treat vision as a first-class fallback for the
roleless tail. A single *clean, lossless* tree is a mirage; a single *identity contract with
documented optional fields + escape hatch + vision fallback* is correct and buildable.

### C.7 Concrete must-fixes before this contract is safe (observation-only)

1. **Build a real device-side `observe(query)` on iOS and Android** — neither exists today
   (only web CSS). On Android add a `findElements` gRPC that runs the predicate device-side
   (close to UiAutomator `By`); on iOS compile to `NSPredicate`/class-chain, but **measure** —
   it often still snapshots (research §3), so the iOS speedup is asymmetric and smaller.
2. **Keep `snapshot()` complete and cross-app** — the bar is the one idb already failed
   (#1209): do not lose TabBar items / system dialogs / all-windows capture. Demote frequency,
   never completeness. Auto-capture a full snapshot + screenshot on every failed observe/assert.
3. **Redesign settle off full-snapshot polling** — Android already has `isWindowUpdating`;
   iOS should adopt a hybrid (native idle/animation cool-off with a hard timeout — *not* raw
   `waitForQuiescence` which #2332 disabled — plus a cheap screenshot/depth-capped fallback).
   The proposal's `device.idle` event must be implemented, not assumed.
4. **Role enum (not String) + name/text/value/placeholder split** — and per-platform
   role-mapping tables (elementType→role, class→role, tagName/ARIA→role) with `rawRole` kept.
5. **Pin `hittable`, `visible`, coordinate space, and merged/unmerged** in the contract with
   explicit per-platform definitions (C.2–C.4), or iOS and Android will silently disagree.
6. **Surface `secure`/`password` and `isHittable` (iOS)** — both are available and cheap and
   currently discarded.

---

## Evidence index (repo files read)

- `maestro-client/.../maestro/{TreeNode,ViewHierarchy,Driver,Maestro,Filters,UiElement,OnDeviceElementQuery,Capability}.kt`
- `maestro-client/.../maestro/drivers/{IOSDriver,AndroidDriver,CdpWebDriver}.kt`
- `maestro-client/.../maestro/utils/ScreenshotUtils.kt`
- `maestro-client/.../maestro/android/chromedevtools/AndroidWebViewHierarchyClient.kt`
- `maestro-client/src/main/resources/maestro-web.js`
- `maestro-android/.../dev/mobile/maestro/{ViewHierarchy,AccessibilityNodeInfoExt}.kt`
- `maestro-ios-driver/src/main/kotlin/hierarchy/AXElement.kt`
- `maestro-ios-xctest-runner/.../Routes/Handlers/ViewHierarchyHandler.swift`,
  `.../Models/AXElement.swift`, `.../Extensions/XCUIElement+Extensions.swift`
- PRs (via `gh pr view`): #169, #787, #1209, #1274, #2332, #2372, #2581, #2872
- Web (May 2026): Detox matchers, EarlGrey GREYMatchers, Flutter SerializableFinder,
  WebdriverIO computed-role, mobile-mcp `list_elements_on_screen`, Playwright aria `[ref=eN]`.
</content>
</invoke>
