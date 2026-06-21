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

## Result (the reproduction)

```
api34-compose  mergeDescendants  FAIL  "no single node exposes merged a11y text [Line 1, Line 2] — reproduces #2704"
api34-compose  contentDescriptor PASS  (sanity: the tree IS readable; only the merge is missing)
api34-native   mergeDescendants  —     (skipped: compose-only capability)
```

The red `mergeDescendants` cell on compose **is** the bug. This is intentionally a **failing
test**: it documents the gap and becomes a regression guard — when the driver learns to surface
merged Compose semantics in the hierarchy, the cell turns green with **no test change**.

## Where a driver fix would go (not done here)

The hierarchy is built from UiAutomator's accessibility nodes. Surfacing merged Compose semantics
would mean reading the merged node's `text`/`contentDescription` (which the platform a11y APIs do
expose) when assembling `contentDescriptor()` — i.e. in the Android driver's hierarchy
construction, not in the fixture. This report scopes the **reproduction**; the fix is follow-up.
