# React Native driver findings — open issues, root-caused + red/green verified

We took the **open** `framework: react-native` issues that the Android RN conformance fixture could
exercise, root-caused each (parallel agents reading the driver hierarchy path + RN rendering), and
**verified each on-device on React Native 0.86 (New Architecture), API 34**, with a red/green design:
each repro screen carries a **control** (should resolve) and the **bug pattern**, and a
framework-scoped behavior pins the outcome.

> Method note: each finding has a fixture screen + a `frameworks = setOf("react-native")` behavior in
> the harness, run via `--framework react-native --command flex,flatlistTestIds,nestedText`. "PASS"
> below is the behavior's verdict; what it *means* is stated per row.

## Summary

| Issue | Filed | On RN 0.86 (New Arch) | Harness verdict | Nature |
|---|---|---|---|---|
| **#2246** `flex:1` hides container+children | 2025-01 | **Does NOT reproduce** — resolves fine | PASS (regression guard) | likely fixed by New Arch/Fabric |
| **#2051** FlatList nested testIDs not found | 2024-09 | **Does NOT reproduce** — nested testIDs resolve | PASS (regression guard) | likely fixed by New Arch/Fabric |
| **#821** nested `<Text>` not addressable | 2023-02 | **Reproduces** | PASS (reproduction) | inherent RN Spannable rendering |
| **#2152** text inside `react-native-svg` | 2024-xx | analysis-only (needs `react-native-svg`) | — | painted canvas text, not in a11y tree |

**Headline:** two of the three reproducible bugs (#2246, #2051) **no longer occur on RN 0.86** — the
newer New-Architecture render path exposes the nodes that older RN/Paper collapsed. We left
**regression-guard** tests so a future RN/driver regression flips them red. #821 is an **inherent RN
limitation** that still reproduces. #2152 is the same "drawn, not in the a11y tree" class as Compose
`mergeDescendants` / Flutter canvas.

---

## How the harness sees elements (shared background)

`AndroidDriver.contentDescriptor()` (maestro-client) calls the on-device `viewHierarchy` RPC →
`maestro-android/.../ViewHierarchy.kt` `dumpNodeRec()` walks the **`AccessibilityNodeInfo`** tree and
serializes each node's `text` / `content-desc` / `hintText` / `resource-id` / `bounds`, recursing
children only when `child.isVisibleToUser` (ViewHierarchy.kt:175). The client parses that to a
`TreeNode`; `TreeBounds.find` matches `resource-id` (suffix) or `text`/`content-desc` (exact). RN
`testID` → Android `resource-id`; `accessibilityLabel` → `content-desc`. **If a string isn't on some
`AccessibilityNodeInfo`, Maestro cannot see it** — every finding below is a variation on that.

---

## #2246 — `<View style={{flex:1}}>` and children "disappear" (Android)

**Report:** as soon as a container View has `flex:1`, it and all children vanish from Maestro on
Android — not findable by testID or accessibilityLabel; removing the style fixes it.

**Root cause (when it occurred):** `dumpNodeRec` does a *hard prune* — `if (child.isVisibleToUser ||
insideWebView)` (ViewHierarchy.kt:175): a child reported as not-visible is skipped **and never
recursed into**, so its whole subtree leaves the dump. A *bare* `flex:1` wrapper (a Yoga layout-only
prop, no native-interesting props) is a prime RN **view-flattening** / `importantForAccessibility`
demotion candidate, and a flex container's first-layout bounds can read empty — any of which makes
`isVisibleToUser` false and removes the subtree. (Confirming nuance: the existing `TreeScreen` flex:1
root already resolves, so "flex:1 always vanishes" was always too strong.)

**Red/green result (RN 0.86, API 34):** **does not reproduce.** Both the control (`flex_ok_*`, fixed
size) and the bug pattern (`flex_bug_*`, bare `flex:1`) resolve. Committed as a **regression guard**
(`FlexLayoutBehavior`, `FlexScreen.tsx`) asserting all four testIDs resolve.

**If it regresses:** the fix belongs in `dumpNodeRec`'s child-visibility gate (ViewHierarchy.kt:175)
— decouple "skip serializing this node" from "skip its subtree" (the same shape as the existing
`insideWebView` escape hatch), and/or stop collapsing non-intersecting bounds to `[0,0][0,0]` in
`getVisibleBoundsInScreen`.

## #2051 — nested testIDs inside a Pressable FlatList row not found

**Report:** the row's testID resolves, but the title/subtitle testIDs *inside* the row don't; Studio
shows only the row.

**Root cause (when it occurred):** RN `Pressable`/`TouchableOpacity` default to `accessible=true`.
Android collapses an `accessible` ViewGroup into a **single atomic accessibility element** and drops
its descendants from the `AccessibilityNodeInfo` tree — so the nested `<Text testID=…>` nodes never
reach `getChild()` in `dumpNodeRec`; only the merged row container survives (carrying its own
`resource-id`). Unlike Compose `mergeDescendants` (#2704, where children survive and only the merged
*text* is empty), here the descendant nodes are *gone*, so no driver text-synthesis can recover them.

**Red/green result (RN 0.86, API 34):** **does not reproduce** — `row_0`, `row_0_title`,
`row_0_subtitle`, plus the non-accessible control (`plain_row`/`plain_title`) all resolve. New-Arch
RN exposes the row's descendants. Committed as a **regression guard** (`FlatlistTestIdBehavior`,
`FlatlistScreen.tsx`).

**If it regresses (older RN / Paper):** user-side fix is `accessible={false}` on the row when inner
elements must be individually targeted; a driver-side fix would need an alternate view-tree source
(like the existing WebView augmentation in `AndroidDriver`), since the a11y nodes are deleted.

## #821 — nested `<Text>` inside `<Text>` not individually addressable — **REPRODUCES**

**Report:** nesting `<Text>` (common for inline styling / inner `onPress`) means Maestro only sees
the outer text; the inner segment can't be resolved or tapped on its own.

**Root cause:** RN renders nested `<Text>` as **one Android `TextView` backed by a `Spannable`** —
the inner `<Text>` is a character-range *span*, not a view. There is exactly one
`AccessibilityNodeInfo` whose `text` is the concatenated `"Outer Inner"`; the inner segment has no
node, no `resource-id` (a span can't carry a testID), no bounds. An inner `onPress` is dispatched as
an `ACCESSIBILITY_CLICKABLE_SPAN` action on the parent, which the hierarchy doesn't surface.
(Structural mirror of #2704: there, many child nodes but no merged parent text; here, one node with
merged text but no child nodes.)

**Red/green result (RN 0.86, API 34):** **reproduces.** Control: a standalone `<Text
testID="standalone_text">` resolves. Bug: no node has `text` exactly `"Inner"` (it exists only inside
the merged `"Outer Inner"` node). Committed as a **reproduction** (`NestedTextBehavior`,
`NestedTextScreen.tsx`) — PASS means the limitation holds; it flips red only if RN/the driver ever
makes spans individually addressable.

**Workaround (what to tell users):** make the tappable text its own element — a sibling `<Text
testID=…>` or a `<Pressable>` around standalone text — so it gets a node/id/bounds. A driver-side fix
(emitting synthetic per-span child nodes with `Layout`-computed bounds + span actions) is possible but
fragile (RTL/wrapping/multiline) and out of proportion to the workaround.

## #2152 — text inside `react-native-svg` not recognized — analysis-only

**Report:** `<Svg><Text>Hello</Text></Svg>` + `assertVisible: "Hello"` fails.

**Root cause:** `react-native-svg` paints the whole SVG onto a single Canvas-backed view
(`RNSVGSvgView`); inner `<Text>` is drawn glyphs (`Canvas.drawText`), **not** an Android `TextView`
with an `AccessibilityNodeInfo`. So `dumpNodeRec` finds no node carrying `"Hello"`, and the
mergeDescendants synthesis (ViewHierarchy.kt:198) can't help (no descendant text node exists). Same
class as Compose `mergeDescendants` / Flutter's single-canvas rendering — drawn content isn't in the
a11y tree.

**Not verified here** because it needs the `react-native-svg` dependency added to the fixture (+
autolinking). **Workaround:** set `accessible` + `accessibilityLabel` (or `aria-label`) on the `Svg`
or the svg `<Text>`, which produces a real `content-desc`; then assert on that label. No general
driver fix is feasible (would require OCR or svg-specific tree introspection).

---

## Artifacts in this branch

- Fixture screens: `conformance-fixtures/react-native/src/screens/{FlexScreen,FlatlistScreen,NestedTextScreen}.tsx`
- Behaviors: `maestro-test/src/main/kotlin/maestro/conformance/behavior/commands/{FlexLayoutBehavior,FlatlistTestIdBehavior,NestedTextBehavior}.kt`
  (all `frameworks = setOf("react-native")`, registered in `ConformanceCli`, screen-mapped in `ScreenFor`).
- Run: `./gradlew :maestro-test:driverConformance --args="--api 34 --framework react-native --command flex,flatlistTestIds,nestedText"` → all PASS (2 regression guards + 1 reproduction).
