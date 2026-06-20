# eraseText FAIL on API 28 — Investigation & Fix

## Issue

The driver-conformance harness reported `eraseText` FAIL on API 28:

> failureReason = "no TEXT_CHANGED with text=ABC past watermark"

The after.png screenshot showed "ABC" in the field — confirming the erase **did work** correctly.

## Root cause

**False-negative in the harness** (not a driver bug).

`EraseTextBehavior` used `Poll.forEvents(…, "TEXT_CHANGED", 5000)`, which returns as soon as
**any** `TEXT_CHANGED` event past the watermark is ingested.  `eraseText(2)` internally calls
`uiDevice.pressDelete()` twice in a tight loop with no sleep between iterations, firing two
rapid `TEXT_CHANGED` events:

```
seq=8  MARK              ← watermark
seq=9  TEXT_CHANGED ABCD ← first delete  (arrived ~16 ms after MARK)
seq=10 TEXT_CHANGED ABC  ← second delete (arrived ~24 ms after MARK)
```

Logcat evidence from the reproduction run (pre-fix):

```
06-21 02:38:22.614  D MAESTRO_FIXTURE: {"seq":7,"event":"TEXT_CHANGED","text":"ABCDE"}
06-21 02:38:24.479  D MAESTRO_FIXTURE: {"seq":8,"event":"MARK"}
06-21 02:38:24.495  D MAESTRO_FIXTURE: {"seq":9,"event":"TEXT_CHANGED","text":"ABCD"}
06-21 02:38:24.519  D MAESTRO_FIXTURE: {"seq":10,"event":"TEXT_CHANGED","text":"ABC"}
```

`command.json` actual (pre-fix):
```json
"actual": { "events": [{"seq":9,"event":"TEXT_CHANGED","text":"ABCD"}] }
```

`Poll.forEvents` returned the moment it ingested seq=9 ("ABCD") — a mere 16 ms after the
MARK.  The seq=10 ("ABC") logcat line had not yet been delivered to the host reader at that
point (only 24 ms after MARK), so the buffer contained only the intermediate "ABCD" event.
`firstOrNull { text == "ABC" }` found nothing → false FAIL.

## Fix

Added `Poll.forMatchingEvent` — a predicate-based poll that **keeps scanning until the exact
expected event arrives** (or timeout elapses), rather than returning on the first event of the
type:

```kotlin
// Poll.kt — new method
fun forMatchingEvent(
    ctx: BehaviorContext,
    w: Watermark,
    type: String,
    timeoutMs: Long = 5000,
    predicate: (FixtureEvent) -> Boolean,
): FixtureEvent?
```

`EraseTextBehavior.run()` now calls:

```kotlin
val match = Poll.forMatchingEvent(ctx, w, "TEXT_CHANGED", 5000) {
    it.payload["text"] == "ABC"
}
```

The fix keeps the test **meaningful**: if `eraseText` does not work, no `TEXT_CHANGED("ABC")`
event is ever emitted, so `forMatchingEvent` times out after 5 s and the behavior returns
`Verdict.fail(…)`.  It also preserves the `APP_EVENT` oracle kind — the assertion is still
grounded in what the app observes, not a screenshot diff.

Files changed:
- `maestro-test/src/main/kotlin/maestro/conformance/behavior/commands/Poll.kt`
- `maestro-test/src/main/kotlin/maestro/conformance/behavior/commands/EraseTextBehavior.kt`

## Verification

Three consecutive runs on API 28 after the fix:

| Run | Verdict | actMs |
|-----|---------|-------|
| 1   | PASS    | 3947  |
| 2   | PASS    | 5233  |
| 3   | PASS    | 5268  |

`command.json` actual (post-fix, all runs):
```json
"actual": { "text": "ABC" }
```

Logcat (run 1, confirms both events arrive and the correct one is matched):
```
seq=8  MARK              ← watermark
seq=9  TEXT_CHANGED ABCD ← intermediate (ignored by forMatchingEvent)
seq=10 TEXT_CHANGED ABC  ← matched ✓
```
