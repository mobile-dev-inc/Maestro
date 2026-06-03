# Observation Model & Cross-Platform Element ("DOM") Shape — Research Findings

> Stress-test of Dimension 2 of `worked-example-core-contract.md`. Focus: should Maestro's new core
> use a typed cross-platform `Element` + cheap targeted `observe(query)` instead of dumping the full
> native view hierarchy on every command? And what *shape* should the cross-platform element model be?

> **Sourcing note:** Claims below are backed by live web research (Apple/Android/Playwright/W3C/Appium/
> Meta docs + issue trackers, retrieved May 2026) plus direct reading of this repo. Specific numbers
> (e.g. "iOS getPageSource >8s") are quoted from the cited sources, not benchmarks I ran. Full source
> list at the bottom.

---

## 0. What Maestro does today (read from the codebase)

Confirmed from `/Users/stevieclifton/codes/Maestro`:

- **`maestro-client/.../maestro/TreeNode.kt`** — the in-memory node type:
  ```kotlin
  typealias Bounds = String   // <-- bounds is a STRING, parsed on demand
  data class TreeNode(
      val attributes: MutableMap<String, String> = mutableMapOf(),  // untyped bag
      val children: List<TreeNode> = emptyList(),
      val clickable: Boolean? = null,
      val enabled: Boolean? = null,
      val focused: Boolean? = null,
      val checked: Boolean? = null,
      val selected: Boolean? = null,
  )
  ```
  Everything else (text, resource-id/accessibilityIdentifier, bounds, hintText, class, etc.) lives in
  the untyped `attributes: Map<String,String>`. `aggregate()` flattens the whole tree into a flat list
  — i.e. Maestro routinely walks **every node**.
- **`maestro-client/.../maestro/ViewHierarchy.kt`** — wraps a root `TreeNode`; `aggregate()` flattens;
  `refreshElement()` re-finds a node by structural equality after a fresh dump.
- **`maestro-client/.../maestro/Driver.kt`** — the driver contract exposes:
  ```kotlin
  fun viewHierarchy(excludeKeyboardElements: Boolean = false): TreeNode
  fun contentDescriptor(excludeKeyboardElements: Boolean = false): TreeNode
  ```
  Both return the **entire tree**. There is no targeted `observe(query)` today.
- **`maestro-client/.../maestro/Maestro.kt`** — ~27 references to the hierarchy; uses a *cached*
  hierarchy within a command and re-dumps after actions / while waiting for the screen to settle.
  So "dump on every command" is roughly true but already softened by per-command caching.
- **iOS** (`IOSDriver.kt`): `viewHierarchy()` delegates to `iosDevice.viewHierarchy()`, which fetches an
  **XCTest accessibility snapshot** over the device channel; `mapHierarchy()` converts the snapshot JSON
  into `TreeNode`. There is dedicated `excludeKeyboardElements` logic — a telling sign that **the full
  snapshot is expensive and noisy enough that they hand-prune the keyboard subtree.**
- **Android**: communication is **gRPC** to an on-device instrumentation app (`maestro-android`,
  `maestro_android.proto`). No `AccessibilityNodeInfo` reference exists in the Kotlin client (0 files) —
  the device-side app produces the hierarchy and ships it over the wire as a proto, which the client
  maps to `TreeNode`.

**Two structural takeaways for the rearchitecture:**
1. The current model is an **untyped attribute bag on a full tree**. The proposed typed `Element` is a
   real improvement in *type safety* regardless of the snapshot-vs-query decision.
2. Maestro **already** prunes (keyboard) and **already** caches per command. So this isn't "full dump,
   always, naively." The honest framing of the proposal is: "make the typed/targeted path primary, and
   make the full tree an explicit, on-demand fallback" — not "we currently dump everything blindly."

---

## 1. WHY full view-hierarchy snapshots exist today

The premise in the question is correct and is the strongest argument *for* keeping a full hierarchy
available: **Maestro is black-box.** No SDK, no app cooperation, works on arbitrary third-party apps
and even across app boundaries (system dialogs, permission prompts, the springboard/launcher). In that
world the **OS accessibility tree is the only universal, app-agnostic way to know what is on screen.**
The snapshot solved several things at once that are hard to get any other way:

- **A stable, queryable representation** the orchestrator can run *arbitrary* predicates against
  (`assertVisible` with regex on text, "the 3rd row", "element below the header", relative selectors,
  index selectors, "contains child with id X"). Maestro's selector language is rich; a full flat list
  (`aggregate()`) makes all of it trivially implementable client-side.
- **Determinism / settle detection.** Comparing successive full snapshots is how you decide "the screen
  stopped changing" (`waitForAppToSettle`). You need a global view to know the *whole* screen is stable,
  not just one element.
- **Cross-app and chrome.** Permission dialogs, share sheets, keyboards, OS overlays — these aren't in
  "the app's" tree at all. A whole-window/whole-screen accessibility dump catches them; a query scoped
  to the app under test might not.
- **One code path for two platforms.** Normalizing both XCTest snapshots and Android accessibility
  nodes into one `TreeNode` shape let the orchestrator be platform-agnostic. This is exactly the value
  the rearchitecture wants to preserve — and the lesson is the *normalization* mattered more than the
  *full-ness*.
- **Vendor reality / prior art.** This is literally how Appium works (`getPageSource` → XML of the
  XCUITest/UiAutomator accessibility tree), and Maestro grew up adjacent to that ecosystem. The
  snapshot was the *known-good* black-box primitive.

So the problem snapshots solved: **"give me a complete, queryable, cross-platform picture of the screen
with zero app integration."** Nothing else gives you *all four* of {no SDK, complete, cross-app,
arbitrary predicates} at once.

---

## 2. What we LOSE moving to targeted `observe(query)` + on-demand snapshot

Be concrete. The proposal keeps the hierarchy but stops dumping it every command. Risks:

1. **Arbitrary / relative / structural predicates break or get slower.** Maestro selectors include
   "below", "above", "leftOf", "containsChild", index, and regex-over-all-text. These are *whole-tree*
   operations. A `observe("button with text Login")` query can't answer "the price label to the right of
   the *second* product card." You'd either (a) push these predicates down into each native query engine
   (XCUITest `NSPredicate` / UiAutomator `BySelector` are **far weaker** than Maestro's selector language
   — see §3), or (b) fall back to a full snapshot anyway. Option (b) means you've added a query API *and*
   still pay for snapshots on a long tail of commands — worst of both.

2. **Settle/idle detection loses its substrate.** "Screen stopped changing" is inherently global. With
   targeted queries you only know *your element* is stable, not the screen. You can get false "ready"
   when an unrelated region is still animating/loading. iOS XCTest already has `waitForQuiescence`
   (built-in idle), but Android settle detection in Maestro leans on snapshot diffing. Removing routine
   snapshots forces a redesign of settle, which is one of the flakiest parts of any E2E tool.

3. **Debugging visibility collapses.** Today every step can log/serialize the full tree → Maestro
   Studio / `maestro hierarchy` / failure artifacts show *everything that was on screen* at the moment
   of failure. With targeted queries, the failure artifact is "we looked for X and didn't find it" with
   **no record of what *was* there.** That is a giant regression in debuggability and is the #1
   complaint users will have. **Mitigation must be mandatory:** on *any* failed `observe`/assertion,
   automatically capture a full snapshot + screenshot for the artifact. If you do that, you've reduced
   *cost* (don't dump on success) without losing *debug completeness* (always dump on failure). This is
   the correct framing and should be written into the contract, not left as an "open question."

4. **`assertNoLongerVisible` / negative assertions / "assert N elements" get awkward.** Proving absence
   is naturally a whole-screen operation. A query that returns `[]` can't distinguish "not present" from
   "query too narrow / role mismatch." Counting ("exactly 3 cells") wants the full set in a region.

5. **Custom/composite widgets with no role.** Canvas-drawn UIs, games, Flutter (which renders to a
   single accessibility node unless semantics are added), custom-drawn lists — these produce sparse or
   roleless trees. A role-keyed query model degrades badly here; a raw tree at least shows *something*
   (bounds, the one node) to fall back on, and vision becomes necessary (see §6). Confirmed: Maestro
   already special-cases Flutter by reading its **Semantics Tree** (Flutter exposes semantics, not a
   native view tree), and already warns when iOS hierarchies get deep (common with RN/Flutter) because
   XCTest.framework errors out on large trees — so "sparse/odd trees break role-keying" is not
   hypothetical but a class of bug Maestro ships mitigations for today.

6. **Staleness across the query/act boundary.** `observe()` returns `Element`s with bounds; by the time
   you `tap(element)` the layout may have shifted (keyboard appeared, banner dismissed). The full-snapshot
   model re-grounds on a fresh tree each step; a cached `Element` handle is more prone to acting on stale
   coordinates. Need a re-resolve-before-act rule.

**Net:** "we're not eliminating the hierarchy, just not dumping it every command" is *only* safe if
(a) failed observations auto-capture a full snapshot, (b) settle detection is redesigned to not depend on
routine snapshots, and (c) complex selectors either compile to native queries or transparently fall back
to a snapshot. Without all three, this is **not** safe — it trades a known performance cost for unknown
flakiness and lost debuggability.

---

## 3. How other frameworks expose UI structure (and the alternatives to full-dump)

### XCUITest — element queries (`XCUIElementQuery`) over a snapshot
- Apple's model is **lazy queries**: `app.buttons["Login"]`, `app.cells.element(boundBy: 2)`. Under the
  hood XCUITest still takes an **accessibility snapshot** of the relevant subtree to resolve the query
  (`XCUIElementSnapshot`), but the public API is *query-shaped*, not tree-shaped.
- `XCUIElementSnapshot` fields (this is a great reference for our `Element` schema):
  `elementType`, `identifier` (accessibilityIdentifier), `label`, `title`, `value`, `placeholderValue`,
  `frame` (CGRect), `isEnabled`, `isSelected`, `isHittable`, plus `children`. Note **two name-ish fields**
  (`label` *and* `title`) and a separate `value` and `placeholderValue` — more nuance than our single
  `name`/`value` pair.
- Cost (confirmed by sources): On iOS, **XPath/page-source triggers a full `XCUIApplication.snapshot()`
  that freezes the UI, captures the entire element tree, converts to XML, then evaluates** — Appium
  reports `getPageSource` taking **>8s on iOS vs <3s on Android** on dense screens, and it's a known
  XCTest limitation ("Apple doesn't provide an API to get the whole element tree" — WDA must recurse).
  Mitigations are exactly `snapshotMaxDepth`/`snapshotMaxChildren`, excluding the expensive `visible`
  attribute, and preferring **id / `NSPredicate` / class-chain** locators (resolved by direct attribute
  lookup, no XML). **Critical asymmetry: on iOS even a "targeted" query frequently still materializes a
  scoped snapshot, so the win is smaller than on Android; on Android `By` returns only the few on-screen
  matches. Measure iOS separately — do not assume the query speedup is symmetric.**
- Source: Apple XCUIElement / XCUIElementSnapshot docs —
  https://developer.apple.com/documentation/xctest/xcuielementsnapshot
  https://developer.apple.com/documentation/xctest/xcuielementquery

### Android UiAutomator — `By`/`BySelector` queries vs `uiautomator dump`
- **`uiautomator dump`** produces a full window hierarchy as XML (the classic "full dump") from
  `AccessibilityNodeInfo`. Slow, whole-window, file-based.
- **`UiDevice.findObject(By.text("Login"))`** with `BySelector` is the targeted alternative: clazz, text,
  desc (contentDescription), res (resource-id), checkable/checked/clickable/enabled/focused/scrollable/
  selected, depth, hasChild/hasDescendant. This maps *very* cleanly onto our proposed `Element` fields —
  Android's selector vocabulary is essentially our schema.
- The data source under both is `AccessibilityNodeInfo`, whose fields are the canonical Android node:
  `className`, `text`, `contentDescription`, `viewIdResourceName` (resource-id), `boundsInScreen`,
  `isClickable/Enabled/Checkable/Checked/Focusable/Focused/Scrollable/Selected/LongClickable/Password`,
  `isVisibleToUser`, `hintText` (API 26+), `stateDescription` (API 30+), `inputType`, `childCount`.
- Source: UiAutomator `BySelector` —
  https://developer.android.com/reference/androidx/test/uiautomator/BySelector ;
  AccessibilityNodeInfo —
  https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo

### Jetpack Compose — the **semantics tree** (distinct from the view tree) — VERY relevant
- This is the single most important data point for "what should the DOM be." Compose has **no View
  hierarchy** for its content; the whole UI is one `AndroidComposeView` host. Accessibility/testing is
  driven by a **separate, parallel `SemanticsTree` of `SemanticsNode`s** that Compose builds explicitly
  from `Modifier.semantics { }` and component-provided semantics. **The render tree (LayoutNodes) and the
  semantics tree are deliberately decoupled.** The semantics tree is the contract for both a11y *and*
  testing (`composeTestRule.onNode(...)`).
- `SemanticsProperties` are the field set (and they're a *much* better-designed schema than raw view
  attributes): `Role` (Button, Checkbox, Switch, RadioButton, Tab, Image, DropdownList), `ContentDescription`,
  `Text`, `EditableText`, `StateDescription`, `ProgressBarRangeInfo`, `Disabled`, `Focused`, `Selected`,
  `ToggleableState` (On/Off/Indeterminate), `TestTag` (the explicit test handle), `Heading`, `IsDialog`,
  `IsPopup`, plus actions (`OnClick`, `ScrollBy`, `SetText`) as first-class semantics.
- **Merged vs unmerged**: Compose exposes *two* views of the same tree. The **merged** tree collapses a
  button-with-icon-and-label into one node with a combined name (what a screen reader / a human "sees" as
  one control). The **unmerged** tree keeps every node. Testing APIs default to merged but allow
  `useUnmergedTree = true`. **This merged/unmerged distinction is the key insight our proposal is
  missing** — see §5. iOS has the same idea informally (accessibility elements merge children).
- Source: Compose semantics —
  https://developer.android.com/develop/ui/compose/testing/semantics ;
  SemanticsProperties —
  https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsProperties

### SwiftUI accessibility
- SwiftUI elements expose accessibility via `.accessibilityLabel`, `.accessibilityValue`,
  `.accessibilityIdentifier`, `.accessibilityAddTraits(.isButton)` etc. The runtime surfaces these as
  `UIAccessibilityElement`s, which XCUITest then snapshots. Like Compose, SwiftUI **synthesizes** an
  accessibility tree that is *not* its layout tree, and it **merges** children by default (a `Button`
  with a `Label` is one accessibility element). Traits (`.isButton`, `.isSelected`, `.isHeader`,
  `.updatesFrequently`) are SwiftUI/UIKit's analog of role+state.
- Source: https://developer.apple.com/documentation/swiftui/view-accessibility ;
  UIAccessibilityTraits — https://developer.apple.com/documentation/uikit/uiaccessibilitytraits

### React Native
- RN renders to **real native views** (UIView / android.view.View), so its accessibility tree *is* the
  native tree — no separate semantics layer. Test handles: `testID` (→ accessibilityIdentifier on iOS,
  resource-id-ish/`view-tag` on Android), `accessibilityLabel`, `accessibilityRole`
  (button, link, header, image, switch, …, a string enum modeled on ARIA), `accessibilityState`
  (`disabled/selected/checked/busy/expanded`). Appium/Detox query this native tree.
- **Two confirmed footguns directly relevant to our model:** (1) On **Android**, RN's `testID` is written
  to the view's **`view-tag`, which UiAutomator/AccessibilityNodeInfo does NOT expose** — so a `testID`
  set by a developer may be **invisible to a black-box accessibility-tree query**, landing in
  `content-desc`/`resource-id` only via extra config. (2) When a parent uses `accessible={true}`, RN
  **flattens its children into a single accessibility node** (great for screen readers, but it *hides the
  individual children from any tree-based query* — they become untestable). **Implication for the
  rearchitecture: a pure accessibility-tree-rooted model will silently lose elements RN devs intended to
  be testable. This is a concrete case where the semantics tree is lossy and you may need vision or a
  raw-view fallback.** (Maestro's own iOS code already nudges RN users to the "new architecture" with
  view flattening — see §0, `WARNING_MAX_DEPTH` insight.)
- Sources: https://reactnative.dev/docs/accessibility ; Appium RN testID/view-tag discussion (appiumpro,
  dev.to "abuse of Accessibility IDs").

### Playwright — ARIA / accessibility snapshot
- Playwright deliberately moved assertions toward the **accessibility tree, not the DOM.** `ariaSnapshot`
  / `toMatchAriaSnapshot` serialize the a11y tree to **YAML** keyed by **role + accessible name**, with
  selected states:
  ```yaml
  - banner:
    - heading "Playwright" [level=1]
  - main:
    - button "Get started"
    - textbox "Search"
    - checkbox "Agree" [checked]
  ```
  Rationale (explicit in their docs): the a11y tree is **stable across DOM refactors**, **human-readable**,
  and **role/name is how users actually perceive UI** — exactly the cross-platform abstraction Maestro
  wants. They include only meaningful states (`[checked]`, `[disabled]`, `[level=N]`, `[expanded]`),
  not every attribute. **This is the closest existing thing to the "right" cross-platform DOM shape and
  is the model I'd anchor on.**
- Source: https://playwright.dev/docs/aria-snapshots ; locators by role —
  https://playwright.dev/docs/locators#locate-by-role

### W3C WebDriver — computed accessibility tree
- The web standards body added **Get Computed Role** and **Get Computed Label** endpoints
  (`GET /session/{id}/element/{id}/computedrole` and `/computedlabel`) precisely so automation can query
  the **computed accessibility role/name** rather than reverse-engineer it from markup. WAI-ARIA defines
  the role taxonomy and the **accessible name & description computation** algorithm (accname). This is the
  industry's formal statement that **role + computed name is the canonical, tool-facing identity of a UI
  element.** If we want a principled `role` enum, base it on the ARIA role taxonomy and map each platform
  into it.
- Sources: WebDriver spec — https://www.w3.org/TR/webdriver2/#get-computed-role ;
  ARIA roles — https://www.w3.org/TR/wai-aria-1.2/#role_definitions ;
  accname — https://www.w3.org/TR/accname-1.2/

**Pattern across all of them:** the *modern* consensus is **role + accessible-name + a small set of
states**, derived from the **accessibility/semantics tree, not the render tree**, queried lazily, with the
full tree available for debugging. Playwright, Compose semantics, SwiftUI a11y, UiAutomator `By`, and
W3C WebDriver all converged here independently. **That convergence is the strongest signal for our shape.**

---

## 4. Prior unified-hierarchy efforts (what shape did each pick, and why)

### Facebook/Meta — Flipper Layout Inspector → "UI Debugger"
- Flipper's original **Layout Inspector** plugin normalized **multiple Android UI systems** (classic
  Views, **Litho/ComponentKit**, Compose, fragments) into **one inspectable node tree** sent to the
  desktop, presenting components "just as if they were native views, exposing all the layout properties,
  props, and state." Confirmed core type: **`Node` is the core data type** — "any UI or data model which
  can be modeled as a **tree of nodes with associated data and attributes** can be inspected." It was a
  **descriptor-based / extensible** design: per-framework descriptors turn a Litho component / Compose
  node / UIView into the generic `Node`; the Litho team plugged into the same mechanism.
- The successor, **UI Debugger**, "is a replacement for the Layout inspector" that **streams the full
  hierarchy of the running app to desktop in near real time** (currently native Android + Litho).
  Note: the docs frame UI Debugger as still streaming — but in practice both are *inspector/debug*
  surfaces (capture-and-view), not a per-test-command hot path. The takeaway holds: Meta's normalization
  layer is **generic-node + typed-attribute-bag + bounds + framework-source ref**, not a fixed flat
  schema.
- **Lesson for us:** Meta, with infinite resources, chose **(a) a generic node with a *typed attribute
  bag*, not a fixed flat schema** (because Litho/Compose/View/UIKit attributes genuinely differ), and
  **(b) descriptor-per-framework normalization**, and **(c) on-demand capture, not constant streaming.**
  This both validates the typed-`Element` direction *and* warns that a single fixed flat schema
  (role/name/value/…) is too rigid — you need an escape hatch for framework-specific attributes.
- Sources: Flipper Layout Inspector — https://fbflipper.com/docs/features/plugins/layout-inspector/ ;
  UI Debugger — https://fbflipper.com/docs/features/plugins/ui-debugger/ ;
  desktop architecture / extending inspector docs on fbflipper.com.

### Appium — page-source XML
- Appium's `getPageSource` returns an **XML serialization of the native accessibility tree** (XCUITest
  snapshot on iOS, UiAutomator nodes on Android), with attributes named per-platform
  (`name/label/value/type/visible/enabled/x/y/width/height` on iOS; `resource-id/text/content-desc/
  class/bounds/clickable/...` on Android). It is the canonical "full-dump" design — and is **notoriously
  slow** for large screens (each `getPageSource` / `findElement` can take seconds on dense iOS UIs).
  Appium added `snapshotMaxDepth`, attribute pruning, and "first-match"/settings to mitigate, and the
  community widely advises **never to poll page source in a loop.**
- **Lesson:** This is exactly the failure mode the rearchitecture is reacting against. It's strong
  evidence *for* targeted queries — but note Appium did **not** remove page-source; they made it tunable
  and kept it for debugging. That's the model: keep it, don't make it the hot path.
- Sources: Appium docs — https://appium.io/docs/en/latest/ ; XCUITest driver `snapshotMaxDepth` /
  performance notes — https://github.com/appium/appium-xcuitest-driver ;
  UiAutomator2 driver — https://github.com/appium/appium-uiautomator2-driver

### Expo / React Native element inspector
- The **RN element inspector** (and Expo's dev menu) overlays a tappable inspector that reports the
  component, its props, box model, and the **accessibility props** for the selected element. It's
  *interactive/targeted* (tap to inspect one node), not a constant full dump — again the on-demand model.
- Source: https://reactnative.dev/docs/debugging ; Expo dev tools docs on docs.expo.dev.

### Android Studio Layout Inspector
- Modern Layout Inspector reads the **live view hierarchy from the running app** (via a device-side agent),
  and for **Compose** it shows the **semantics tree alongside the layout tree** — explicitly presenting
  *both* the recomposition/layout structure and the merged/unmerged semantics. It captures **per-frame
  snapshots on demand** (and a "live updates" mode that is acknowledged as expensive). It surfaces
  Compose recomposition counts — a render-tree concern that has *no* equivalent in the accessibility tree.
- **Lesson:** Google itself presents semantics tree and view tree as **two different things for two
  different jobs** (a11y/testing vs render debugging) — reinforcing the §5 conclusion that we must pick
  the *semantics* tree as the spine and treat render info as optional debug metadata.
- Source: https://developer.android.com/studio/debug/layout-inspector

### Google/Apple "accessibility tree" standardization
- The accessibility tree itself *is* the de-facto cross-platform standardization layer: ARIA roles
  (web), Android `AccessibilityNodeInfo` + Compose `Role`, Apple `UIAccessibilityTraits` /
  `NSAccessibility`. They aren't a single spec, but they **share a vocabulary (role/name/value/state)**
  because they all serve assistive tech. **A test tool that adopts this vocabulary inherits a stable,
  vendor-maintained, app-cooperation-free abstraction — which is the whole point of going black-box.**

**Synthesis of prior art:** Everyone who built a cross-platform/cross-framework hierarchy chose **(1) a
generic node with a typed attribute bag** (not a fixed flat record), **(2) normalized via
per-framework descriptors**, **(3) captured on-demand / lazily**, and **(4) rooted in the
accessibility/semantics tree** for the *identity* fields while keeping render info as optional metadata.

---

## 5. THE SHAPE QUESTION — and a critique of the proposed `Element`

### Spine: semantics/accessibility tree, NOT the render/view tree, with a render-tree escape hatch
Root the cross-platform model in the **accessibility/semantics tree**. Reasons, all from §3–§4:
- It's the **only thing that exists cross-platform without an SDK** (the original reason for snapshots).
- It's **stable across UI refactors** (Playwright's explicit rationale).
- It's what every modern framework (Compose semantics, SwiftUI a11y, RN roles, ARIA, WebDriver computed
  role) has converged on as the *tool-facing* identity.
- The render/view tree is **leaky and platform-specific** (Compose has *no* view tree; iOS layer trees
  are huge and meaningless; recomposition counts are render-only). Render info is useful for *debugging
  geometry* but is noise for *finding/asserting*.

**But** make it a *pragmatic hybrid*: the spine is semantics, with **(a)** raw render-derived geometry
(`bounds`) because you must tap pixels, and **(b)** an explicit **`raw`/`extra` typed attribute bag** for
framework-specific fields you can't model (Litho/Compose specifics, web ARIA extras, `placeholderValue`).
Flipper, Meta's own answer, did exactly this. A fixed flat schema with no escape hatch will be
under-expressive within a quarter.

### Critique of the proposed schema
```kotlin
data class Element(
    val role: String, name: String?, value: String?,
    val state: ElementState,            // enabled, selected, focused, checked
    val bounds: Bounds, visible: Boolean, hittable: Boolean, scrollable: Boolean,
    val nativeId: String?,
)
```

**What's right:** role/name/value/bounds/hittable/nativeId is the correct *core* — it matches XCUIElement-
Snapshot, UiAutomator `By`, and Playwright's role+name+states. Typed beats today's string bag. Good.

**What's wrong / missing (concrete):**

1. **`role: String` should be a closed enum (or enum + `rawRole: String`).** A free string invites
   per-platform drift ("button" vs "Button" vs "XCUIElementTypeButton" vs "android.widget.Button").
   Define a canonical role taxonomy (base it on ARIA / Compose `Role`) and normalize each platform into
   it; keep the platform's raw type string in the attribute bag. Without this, the orchestrator's matching
   logic re-acquires the platform-specificity you're trying to remove. **This is the sharpest schema bug.**

2. **No `id`/`stableKey` for re-resolution.** `nativeId` is the *developer-assigned* id (often absent).
   You also need an **engine-stable handle** to re-resolve "the same element" across a re-observe (for
   act-after-observe and staleness, §2.6). XCUITest queries and Compose semantics both have node identity;
   expose it. Otherwise the staleness/re-resolve story has nothing to hang on.

3. **One `name` collapses real distinctions.** iOS has **`label` AND `title` AND `placeholderValue`**;
   Compose has `ContentDescription` AND `Text` AND `EditableText`; Android has `text` AND
   `contentDescription` AND `hintText`. Collapsing all to `name`/`value` will mis-match: a search box's
   placeholder ("Search") is *not* its value, and an icon button's contentDescription is *not* visible
   text. Recommend: `name` (computed accessible name), `text` (visible/displayed text), `value` (current
   value), `placeholder` (hint). This mirrors the accname computation and all three platforms.

4. **`state: ElementState` is under-specified.** `enabled/selected/focused/checked` misses:
   - **`checked` should be tri-state** (`true/false/indeterminate`) — Compose `ToggleableState`, ARIA
     `aria-checked="mixed"`. A boolean loses indeterminate checkboxes.
   - **`expanded`** (collapsibles/accordions/comboboxes) — common in real apps, in ARIA & RN state.
   - **`busy`/loading** — needed for settle/wait logic (RN `accessibilityState.busy`, ARIA `aria-busy`).
   - **`editable`/`secure(password)`** — needed to choose input strategy and to redact secure fields.
     `secureTextEntry`/`isPassword` exists on both platforms.
   - **`stateDescription`** (Compose `StateDescription`, Android API 30+) — free-text state.

5. **`merged` vs `unmerged` is entirely absent — and it's pivotal (see §3 Compose, SwiftUI).** Confirmed
   from Compose docs: there are literally **two trees** — the **merged** tree (collapses descendants when
   `mergeDescendants=true`; **this is what Compose's testing framework uses by default**) and the
   **unmerged** tree (`useUnmergedTree=true`; every node intact; **accessibility services use this** and
   apply their own merging). Decide and put it in the contract: does `observe()` return merged (one node
   per perceived control — what users tap, what assertions usually mean) or unmerged (every node)?
   Recommend **merged by default**, with `unmerged` for power selectors/debugging. If undecided, iOS and
   Android will silently disagree (XCUITest's accessibility merging differs from a raw Android dump *and*
   from Compose-merged), and "button with text Login" will match on one platform and not the other.
   **This is the single biggest correctness gap in the current proposal.** Note the contract's
   `Element.children`/projection examples implicitly assume *some* merge policy but never name it.

6. **`visible` and `hittable` need precise, shared definitions.** "visible" can mean "in tree" vs
   "within window bounds" vs "not occluded by another element" vs "alpha>0". Android `isVisibleToUser`,
   iOS snapshot has no true occlusion bit (XCUITest infers hittability via hit-testing the center point).
   Pin the semantics or the two platforms will diverge. Add **`enabled`** at top level too (it's identity-
   adjacent and every selector uses it; currently buried in `state`, fine, but be consistent).

7. **No hierarchy/relationship info on `Element`.** Relative selectors (below/leftOf/containsChild) and
   index/“nth” need *some* structure. Either keep `parent`/`children`/`index`/`depth` on the element, or
   accept that `observe(query)` **cannot** serve relative selectors and they must trigger a snapshot.
   Pick explicitly. (Recommend: include lightweight `childCount` + an opaque `parentRef`, and let
   relative/structural selectors request a scoped subtree.)

8. **`bounds: Bounds` — define the type and the coordinate space.** Today `Bounds = String` (a parse-on-
   demand hack). Make it a real `{x,y,w,h}` in a **named, documented coordinate space** (screen points?
   pixels? pre/post status-bar?). iOS points vs Android pixels is a classic cross-platform bug. Also
   consider `centerForTap` separately from `bounds` (hit target may not be the geometric center).

9. **Missing: a `frameworkSource` / `kind` tag** ("native-view" | "compose" | "webview" | "flutter" |
   "unknown") so the orchestrator can adapt (e.g. WebViews need DOM bridging; Flutter needs semantics
   enabling; canvas/unknown → vision). Cheap to add, high value.

### Recommended revised schema (summary)
```
Element {
  role: Role(enum) + rawRole: String
  name: String?            // computed accessible name (accname)
  text: String?            // visible text
  value: String?           // current value
  placeholder: String?     // hint/placeholder
  state: { enabled, focused, selected, checked: TriState, expanded?, busy?,
           editable?, secure?, stateDescription? }
  bounds: Rect(x,y,w,h, space=screenPoints) ; centerForTap: Point?
  visible: Boolean (defined: within window & not occluded)
  hittable: Boolean
  scrollable: Boolean
  nativeId: String?        // resource-id / accessibilityIdentifier
  engineRef: String        // stable handle for re-resolution
  childCount: Int ; parentRef: String?   // minimal structure for relative selectors
  frameworkSource: enum    // native | compose | swiftui | webview | flutter | unknown
  extra: Map<String,Value> // typed escape hatch (placeholderValue, recompositions, ARIA…)
}
```

---

## 6. Hierarchy vs targeted native queries vs vision/screenshots

A layered policy, cheapest-and-most-reliable first:

1. **Targeted native query (`observe(query)`) — the default hot path.** For the 90% case
   (role+name+id+text, with simple states), compile to XCUITest `NSPredicate` / UiAutomator `BySelector` /
   Compose semantics matcher. Cheap, native, returns typed `Element`s. **Caveat from §3: on iOS this often
   still materializes a scoped snapshot — so "query" buys *less* than on Android. Measure before assuming
   the win is symmetric.**

2. **Scoped subtree / full snapshot — explicit fallback, always on failure.** Use it for: relative &
   structural selectors, counting, negative assertions, settle detection, and — **non-negotiable** — to
   capture a complete debug artifact **whenever an observe/assert fails** (preserve today's debuggability;
   §2.3). Keep the full-tree API; just demote it from "every command" to "on demand + on failure."

3. **Vision / screenshots — last resort and ground truth.** Required for: canvas/game/unknown UIs,
   Flutter without semantics, occlusion checks the tree can't express, visual assertions (color, image,
   exact layout), and as a sanity cross-check that the tree matches what's drawn (the tree can lie —
   off-screen nodes, alpha-0, animating). With LLM/vision now viable, vision is a real third leg, but it's
   slow, nondeterministic, and costs tokens — use it to *disambiguate or verify*, not as the primary
   locator. Always pair a vision-found target with a re-grounding tap on real coordinates.

**Reliance ratio recommendation:** ~70–80% targeted queries, ~15–25% on-demand snapshot (incl. all
failures + settle), ~5% vision. And **always** attach screenshot + full snapshot to failure artifacts.

---

## 7. Bottom line / recommendation

- **Yes** to typed `Element` + targeted `observe()` as the *primary* path — it matches where the entire
  industry converged (Playwright ARIA snapshots, UiAutomator `By`, XCUITest queries, Compose semantics,
  W3C computed role). The current untyped `TreeNode` attribute-bag-on-full-tree is the right thing to
  replace.
- **Root the model in the accessibility/semantics tree, not the render/view tree**, as a pragmatic hybrid
  (semantics identity + render-derived bounds + typed `extra` escape hatch). This is the consensus shape
  and the one Meta's Flipper, Google's Layout Inspector, and Playwright all landed on.
- **But the proposal is not safe as written.** Three things must move from "open question" to "in the
  contract," or the migration trades known cost for unknown flakiness/lost debuggability:
  1. **Merged-vs-unmerged must be decided** (default merged) — currently absent and it will cause silent
     iOS/Android matching divergence. *(Sharpest correctness gap.)*
  2. **`role` must be a normalized enum**, not a free `String` — otherwise platform-specificity leaks
     straight back into the orchestrator. And `name`/`value` must split into name/text/value/placeholder.
  3. **Failure-path full-snapshot capture and a redesigned (non-snapshot-diff) settle detector are
     mandatory** — without them you lose debuggability and break wait/idle, which are the flakiest parts
     of E2E.
- **Don't delete the full hierarchy.** Demote it. Appium kept page-source-but-tuned; Meta kept capture-
  on-demand; that's the proven middle path. "We're not eliminating the hierarchy, just not dumping it
  every command" is correct *only if* (1)+(3) above are honored.

---

## Sources (retrieved May 2026)
- Apple XCUIElementSnapshot / XCUIElementQuery: https://developer.apple.com/documentation/xctest/xcuielementsnapshot , https://developer.apple.com/documentation/xctest/xcuielementquery
- Apple SwiftUI accessibility / UIAccessibilityTraits: https://developer.apple.com/documentation/swiftui/view-accessibility , https://developer.apple.com/documentation/uikit/uiaccessibilitytraits
- Android UiAutomator BySelector: https://developer.android.com/reference/androidx/test/uiautomator/BySelector
- Android AccessibilityNodeInfo: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- Jetpack Compose semantics (merged/unmerged): https://developer.android.com/develop/ui/compose/testing/semantics
- Compose SemanticsProperties: https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsProperties
- Android Studio Layout Inspector (view + Compose semantics): https://developer.android.com/studio/debug/layout-inspector
- React Native accessibility (testID, role, state): https://reactnative.dev/docs/accessibility
- Playwright ARIA snapshots: https://playwright.dev/docs/aria-snapshots ; locate by role: https://playwright.dev/docs/locators
- W3C WebDriver computed role/label: https://www.w3.org/TR/webdriver2/#get-computed-role
- WAI-ARIA roles: https://www.w3.org/TR/wai-aria-1.2/ ; accname: https://www.w3.org/TR/accname-1.2/
- Flipper Layout Inspector: https://fbflipper.com/docs/features/plugins/layout-inspector/ ; UI Debugger: https://fbflipper.com/docs/features/plugins/ui-debugger/
- Appium page source + XCUITest snapshotMaxDepth/perf: https://appium.github.io/appium-xcuitest-driver/latest/reference/settings/ , https://appium.github.io/appium-xcuitest-driver/latest/troubleshooting/wda-slowness/ , https://github.com/appium/appium/issues/7029 , https://github.com/appium/appium/issues/18665 (iOS getPageSource >8s)
- Why Appium tests are slow / locator strategy (XPath triggers full snapshot): https://medium.com/@mayvinr/why-your-appium-tests-are-slow-it-starts-with-your-locators-0be86cc6935c , https://appiumpro.com/editions/60-how-to-pick-the-right-locator-strategy
- Compose merging/clearing (merged vs unmerged, printToLog useUnmergedTree): https://developer.android.com/develop/ui/compose/accessibility/merging-clearing , https://developer.android.com/develop/ui/compose/testing/apis
- RN testID → view-tag invisible to UiAutomator; accessible={true} flattens children: https://appiumpro.com/editions/76-testing-react-native-apps-with-appium , https://dev.to/nextlevelbeard/an-end-to-the-abuse-of-accessibility-ids-5d2j
- Flipper UI Debugger / Layout Inspector Node format: https://github.com/facebook/flipper/blob/main/desktop/plugins/public/ui-debugger/docs/overview.mdx , https://fbflipper.com/docs/extending/supporting-layout , https://engineering.fb.com/2018/06/11/android/flipper/
- Maestro docs (Flutter via Semantics Tree; large-hierarchy perf/insights): https://docs.maestro.dev/cli/view-hierarchy , https://docs.maestro.dev/get-started/supported-platform/flutter , https://github.com/mobile-dev-inc/maestro/blob/main/CHANGELOG.md
- WebDriverIO computed role/label: https://webdriver.io/docs/api/element/getComputedRole/
- Maestro current code (this repo): `maestro-client/src/main/java/maestro/{TreeNode,ViewHierarchy,Driver,Maestro}.kt`, `maestro-client/.../drivers/{IOSDriver,AndroidDriver}.kt`, `maestro-android/src/main/proto/maestro_android.proto`
