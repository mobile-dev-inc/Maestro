# Compose longPress — Finding

## Summary

`longPress` works on the Compose fixture **with no driver change** once the fixture uses an
idiomatic `combinedClickable`. The original failure was caused by a **cheat in the fixture**, not a
driver bug — confirming the harness's core premise: test against idiomatic UI, fix the driver only
when idiomatic UI exposes a real gap.

## Root cause (the cheat that was removed)

The first Compose fixture detected long-press with a hand-rolled gesture loop:

```kotlin
// CHEATED (removed) — manual hold-timer via raw pointer events
Box(modifier = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown()
            // … measured hold duration manually, cancelled on MOVE
        }
    }
})
```

`AndroidDriver.longPress()` issues `adb shell input swipe x y x y 3000`. Even with identical
start/end coordinates, `input swipe` emits repeated MOVE events. The manual loop treated those
MOVEs as a drag and cancelled its hold timer, so `LONG_PRESS` was never emitted → the command
looked broken.

## The fix (in the fixture, not the driver)

Replace the hand-rolled detector with the standard Compose API — `combinedClickable`:

```kotlin
Box(
    modifier = Modifier
        .semantics { contentDescription = "longpress_target" }
        .background(MaterialTheme.colorScheme.primaryContainer)
        .combinedClickable(
            onClick = { /* no-op */ },
            onLongClick = {
                FixtureEmitter.emit("LONG_PRESS",
                    mapOf("target" to "longpress_target", "downMs" to 3000))
            }
        )
) { Text("longpress", color = MaterialTheme.colorScheme.onPrimaryContainer) }
```

`combinedClickable` is the idiomatic combined tap + long-press handler. It does **not** cancel on
MOVE when the pointer stays near the down position, so `input swipe x y x y 3000` triggers
`onLongClick` after the system long-press timeout — exactly as a real app would behave.

**Design rule:** the long-press target must **not** wrap an inner `Button`/`clickable`. A child
clickable consumes the pointer-down before `combinedClickable` can start its hold timer, so the
long-click never fires. Use a plain `Box`/`Surface`.

## Driver: no change

`AndroidDriver.longPress()` keeps its original implementation:

```kotlin
override fun longPress(point: Point) {
    dadb.shell("input swipe ${point.x} ${point.y} ${point.x} ${point.y} 3000")
}
```

> **Note on a discarded experiment.** During investigation a dedicated `longPress` gRPC RPC was
> prototyped in `maestro_android.proto` + the on-device server (via `InteractionController
> .longTapNoSync`). It was found that `longTapNoSync` does **not** trigger `combinedClickable
> .onLongClick` (different gesture-injection path), so the prototype was **reverted in full** —
> there are no proto/server/shipped-APK changes. The shipped driver path is unchanged.

## Verification

`longPress` is green on **both** frameworks across every API the harness could boot
(24–35; API 24–27 only differ on the unrelated `killApp` platform limitation):

```
api24..35  native  longPress  PASS
api24..35  compose longPress  PASS
```
