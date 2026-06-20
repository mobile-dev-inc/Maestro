# hideKeyboard Conformance Failure — Root Cause and Fix

## Issue

The driver-conformance harness reported `hideKeyboard` FAIL on API 24, 25, and 35 with
`failureReason = "IME did not appear after tapping text_field (pre-condition failed)"`.
The keyboard precondition check fired before `hideKeyboard()` was ever exercised, making
the result a false failure.

## Root Cause

**Root cause: `isKeyboardVisible()` was GBoard-only; `google_apis` images ship AOSP LatinIME.**

`AndroidDriver.isKeyboardVisible()` detected a visible keyboard by searching the serialized
view hierarchy for the string `"com.google.android.inputmethod.latin:id"` — the GBoard package.

On `google_apis` images (the variant used for API ≤35 in `FreshAvdProvider`), GBoard is absent.
The active IME is AOSP LatinIME, package `com.android.inputmethod.latin` (no `google.` prefix).

Evidence from `adb -s emulator-5554 shell ime list -s` on a freshly provisioned API 24 AVD:

```
com.android.inputmethod.latin/.LatinIME
```

`adb shell pm list packages | grep latin` returned only:

```
package:com.android.inputmethod.latin
```

GBoard (`com.google.android.inputmethod.latin`) was not installed at all.

`FreshAvdProvider.pinGboardIme()` already handled this case with a warning and a no-op, but
`isKeyboardVisible()` was never updated to match the AOSP IME, so it always returned `false`
after the text field was tapped — even though the AOSP keyboard actually appeared — causing
the precondition check in `HideKeyboardBehavior` to fail.

This is **Cause 3** from the investigation list: the GBoard-specific string grep in
`isKeyboardVisible()` does not match the image's actual IME package.

## Fix

**Decision: real PASS via detection fix + IME pinning generalization.**

Two changes:

### 1. `maestro-client/.../AndroidDriver.kt` — `isKeyboardVisible()`

Extended the hierarchy search to match either IME package:

```kotlin
val hierarchyJson = jacksonObjectMapper().writeValueAsString(root)
"com.google.android.inputmethod.latin:id" in hierarchyJson ||
    "com.android.inputmethod.latin:id" in hierarchyJson
```

GBoard (`com.google.android.inputmethod.latin`) is matched on `google_apis_playstore` images;
AOSP LatinIME (`com.android.inputmethod.latin`) is matched on `google_apis` images. Both
actually render keyboard UI in the view hierarchy when active.

### 2. `maestro-test/.../device/FreshAvdProvider.kt` — `pinGboardIme()` → `pinUsableIme()`

Generalized the method to fall back to AOSP LatinIME when GBoard is absent:

```
Pinned GBoard IME (...)           — google_apis_playstore images
Pinned AOSP LatinIME (...)        — google_apis images (API ≤35)
Warning: no usable soft keyboard  — genuinely keyboardless image
```

This ensures an IME is explicitly activated before the test runs, so `isKeyboardVisible()`
can actually return `true` and `HideKeyboardBehavior` proceeds past the precondition.

No new libraries, no SKIP/NOT_APPLICABLE plumbing needed — the keyboard IS present and
functional on these images; only the detection was wrong.

## Verification

Three consecutive harness runs on API 24 (`google_apis` / `arm64-v8a`):

| Run | hideKeyboard | isKeyboardVisible |
|-----|-------------|-------------------|
| 1   | PASS        | PASS              |
| 2   | PASS        | PASS              |
| 3   | PASS        | PASS              |

Command: `./gradlew :maestro-test:driverConformance --args="--api 24 --framework native --command hideKeyboard,isKeyboardVisible --record all --out /tmp/hk24"`

Emulator stopped and AVD deleted after verification.
