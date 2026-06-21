# Compose `mergeDescendants` — Reproduced Driver Gap (#2704)

Tracks **[mobile-dev-inc/Maestro#2704](https://github.com/mobile-dev-inc/Maestro/issues/2704)**
("Android Compose `mergeDescendants`"). Unlike the `longPress`/`pressKey` findings — which turned
out to be fixture cheats with no driver bug — this one is a **genuine driver/hierarchy gap**, and
the conformance harness now reproduces it as a permanent, cross-API signal.

## The bug

A Compose node with `Modifier.semantics(mergeDescendants = true)` folds its children's semantics
(text, etc.) into one accessibility node. Screen readers and Layout Inspector see the merged
content — TalkBack announces "Line 1, Line 2". But in Maestro's hierarchy the merged parent comes
through with **empty** `text` / `accessibilityText` / `hintText`, while the children keep their
individual texts on separate nodes. So:

```yaml
- assertVisible:
    text: "Line 1, Line 2"   # fails — no node carries the merged text
```

This is a real gap between what accessibility services expose and what Maestro's UiAutomator-based
hierarchy surfaces (matters for the European Accessibility Act / Android TV a11y).

## How the harness reproduces it

- **Fixture** (compose only) — `screens/MergeScreen.kt`, the exact idiomatic repro:
  ```kotlin
  Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
      Text("Line 1"); Text("Line 2")
  }
  ```
- **Behavior** — `MergeDescendantsBehavior` (`name = "mergeDescendants"`, `coverage =
  FRAMEWORK_SENSITIVE`, `frameworks = setOf("compose")`). It walks `driver.contentDescriptor()` and
  requires that **one** node carries **both** child texts in its a11y attributes (robust to the
  exact separator Compose uses). No merged node → FAIL.
- **Framework scoping** — `mergeDescendants` has no native equivalent, so `CommandBehavior` gained
  an optional `frameworks: Set<String>?` (null = all). The runner skips a behavior on frameworks it
  doesn't apply to, so the **native** matrix cell is left **blank**, not failed.

## Root cause (confirmed on-device, API 34)

Dumping the live accessibility subtree for `MergeScreen` showed the merge does **not** collapse the
children — and the merged node carries no text:

```
ComposeView
  View (children=5)              ← the merged Column: text='' content-desc='' hintText=''
    TextView text='Line 1'  content-desc=''   ← children remain SEPARATE, each with own text
    TextView text='Line 2'  content-desc=''
```

So at the accessibility layer the merged Column node is **screen-reader-focusable but has empty
`text`/`contentDescription`**, while its child `Text`s persist as distinct nodes. No single node
carries both strings → `assertVisible: text: "Line 1, Line 2"` finds nothing.

The hierarchy is serialized on-device in
`maestro-android/src/androidTest/java/dev/mobile/maestro/ViewHierarchy.kt` (`dumpNodeRec`), which
read `AccessibilityNodeInfo.text` and `.contentDescription` verbatim — both empty for the merged
node — so the merged content never reached the client.

## The fix (implemented + verified)

`ViewHierarchy.dumpNodeRec` now detects exactly this shape and back-fills the merged text:

> if a node `isScreenReaderFocusable` (Compose's merged-node marker) **and** its own `text` and
> `contentDescription` are empty **and** its descendants carry text, synthesize `content-desc` by
> joining the descendants' text/contentDescription in traversal order ("Line 1, Line 2") — exactly
> what TalkBack announces.

**Tightly scoped — no blast radius on normal UIs:** ordinary merged nodes (Buttons, list items,
cards) already expose their own `text`/`contentDescription`, so the `empty own text & desc`
condition is false and the back-fill never fires. It only activates on the otherwise-empty merged
container that #2704 is about. Children keep their individual texts (nothing is removed), so
existing selectors are unaffected.

Files: `maestro-android/.../ViewHierarchy.kt` (logic) + rebuilt bundled `maestro-server.apk`
(androidTest) + refreshed `maestro-android-source.sha256` sentinel.

## Result

Before → after, same `mergeDescendants` conformance behavior on `api34-compose`:

```
BEFORE  mergeDescendants  FAIL  "no single node exposes merged a11y text [Line 1, Line 2]"
AFTER   mergeDescendants  PASS  merged node content-desc='Line 1, Line 2' (children unchanged)
```

The compose-only `mergeDescendants` behavior — added as the failing reproduction — is now the
**regression guard** for the fix. Native is unaffected (the behavior is compose-scoped and skipped
there).

## For reviewers

This changes the **on-device hierarchy output for all Android apps**, so it ships as a separate,
reviewable commit/branch (`fix/compose-mergedescendants-2704`) rather than riding along with the
fixture/harness work. The conformance branch holds the reproduction; this branch holds the fix +
the rebuilt `maestro-server.apk`.
