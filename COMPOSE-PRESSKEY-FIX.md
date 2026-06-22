# Compose pressKey — Finding

## Summary

`pressKey` works on the Compose fixture **with no driver change** once the fixture captures key
events the idiomatic Compose way. As with `longPress`, the original problem was a **fixture cheat**,
not a driver bug.

## Root cause (the cheat that was removed)

The first Compose fixture embedded a View-based `EditText` via `AndroidView` purely so the existing
ADB key-injection would land somewhere familiar:

```kotlin
// CHEATED (removed) — a platform View, not Compose
AndroidView(factory = { ctx ->
    EditText(ctx).apply {
        setOnKeyListener { _, keyCode, event -> /* emit KEY */; false }
    }
})
```

This sidesteps Compose entirely, so it proved nothing about whether Maestro's `pressKey` works
against a real Compose surface.

## The fix (in the fixture, not the driver)

Use Compose's `OutlinedTextField` with `Modifier.onKeyEvent` for hardware/physical keys and
`KeyboardActions.onDone` for the IME "Done" action:

```kotlin
OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { FixtureEmitter.emit("KEY", mapOf("code" to "ENTER")) }),
    modifier = Modifier
        .semantics { contentDescription = "text_field" }
        .onKeyEvent { keyEvent ->
            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                val code = when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> "ENTER"
                    KeyEvent.KEYCODE_DEL   -> "DEL"
                    KeyEvent.KEYCODE_BACK  -> "BACK"
                    KeyEvent.KEYCODE_TAB   -> "TAB"
                    KeyEvent.KEYCODE_SPACE -> "SPACE"
                    else -> keyEvent.nativeKeyEvent.keyCode.toString()
                }
                FixtureEmitter.emit("KEY", mapOf("code" to code))
            }
            false
        }
)
```

Both `onKeyEvent` and `KeyboardActions` are idiomatic Compose. `AndroidDriver.pressKey()` keeps
using `dadb.shell("input keyevent …")`, which dispatches to the focused `TextField` and fires
`onKeyEvent` — no driver change.

## Verification

`pressKey` is green on **both** frameworks across every API the harness could boot (24–35).
