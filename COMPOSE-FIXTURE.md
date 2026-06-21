# Compose Conformance Fixture

## Purpose

The Compose fixture (`maestro-test/conformance-fixtures/compose/`) proves Maestro's `AndroidDriver`
works against a **real, idiomatic Jetpack Compose app** — built exactly as a Compose team would,
with no `AndroidView` escape hatches, no hand-rolled gesture detectors beyond what Compose itself
provides, and no workarounds.

**Operating rule:** when a behavior fails, the fix goes in the **driver or harness — never the
fixture**. The only time the fixture changed was to *remove cheats* (see the longPress/pressKey
findings), which is the opposite of fixing the app to dodge a driver bug.

## Screen inventory

| Screen | Route key | Commands exercised |
|--------|-----------|--------------------|
| `TapScreen` | `TapScreen` | tap, longPress |
| `InputScreen` | `InputScreen` | inputText, eraseText |
| `KeyboardScreen` | `KeyboardScreen` | pressKey, isKeyboardVisible, hideKeyboard |
| `SwipeScreen` | `SwipeScreen` | swipeDirection, swipeElement, swipeStartEnd |
| `ScrollScreen` | `ScrollScreen` | scrollVertical |
| `TreeScreen` | `TreeScreen` | contentDescriptor |
| `MergeScreen` | `MergeScreen` | mergeDescendants (compose-only — see #2704) |
| `OrientationScreen` | `OrientationScreen` | setOrientation |
| `AnimationScreen` | `AnimationScreen` | waitUntilScreenIsStatic |
| `AppLifecycleScreen` | `AppLifecycleScreen` | launchApp, stopApp, killApp, clearAppState |
| _(driver-level)_ | — | backPress, openLink, takeScreenshot, waitForAppToSettle |

## Key design decisions

- **`testTagsAsResourceId = true`** at the `Router` root, so `testTag`s surface as `resource-id` in
  the accessibility tree (what Maestro's `id:` selector reads). The idiomatic way a Compose team
  exposes test IDs.
- **Long-press via `combinedClickable`** on a plain `Box` (no inner clickable child). See
  [COMPOSE-LONGPRESS-FIX.md](COMPOSE-LONGPRESS-FIX.md).
- **Keyboard via `OutlinedTextField` + `onKeyEvent` / `KeyboardActions`** — no `AndroidView`. See
  [COMPOSE-PRESSKEY-FIX.md](COMPOSE-PRESSKEY-FIX.md).
- **Element identity** mixes `contentDescription`, `testTag`, and visible `Text` to exercise
  Maestro's `id:` / `text:` / `accessibility id:` resolver strategies.

## Findings

1. **longPress** — *fixture cheat, no driver bug.* A hand-rolled `awaitPointerEventScope` hold-timer
   was cancelled by `input swipe`'s MOVE events. Idiomatic `combinedClickable` works with the
   unchanged driver. ([COMPOSE-LONGPRESS-FIX.md](COMPOSE-LONGPRESS-FIX.md))
2. **pressKey** — *fixture cheat, no driver bug.* `AndroidView(EditText)` was masking the question;
   idiomatic `OutlinedTextField` + `onKeyEvent` works with the unchanged driver.
   ([COMPOSE-PRESSKEY-FIX.md](COMPOSE-PRESSKEY-FIX.md))
3. **mergeDescendants** — *genuine driver gap, reproduced as a failing test.* Compose
   `mergeDescendants = true` content is not surfaced in Maestro's hierarchy.
   ([COMPOSE-MERGEDESCENDANTS-2704.md](COMPOSE-MERGEDESCENDANTS-2704.md), tracks
   [#2704](https://github.com/mobile-dev-inc/Maestro/issues/2704))

## Running

```bash
# All Tier-A commands, both frameworks, fresh provisioned emulator:
./gradlew :maestro-test:driverConformance \
    --args="--api 34 --framework native,compose --record on-failure"

# Just the #2704 reproduction:
./gradlew :maestro-test:driverConformance \
    --args="--api 34 --framework compose --command mergeDescendants"
```

## Result matrix (Tier-A, 22 commands)

Fresh-provisioned arm64 emulators, one API at a time.

| API | native | compose | notes |
|-----|--------|---------|-------|
| 24  | 21/22  | 21/22   | `killApp` only — `am kill` can't reap a foreground process on 24–27 (platform, both frameworks) |
| 25  | 21/22  | 21/22   | same `killApp` platform limit |
| 26  | 21/22  | 21/22   | same |
| 27  | 21/22  | 21/22   | same |
| 28  | 22/22  | 22/22   | clean |
| 29  | 22/22  | 22/22   | clean |
| 30  | 22/22  | 22/22   | clean |
| 31  | 22/22  | 22/22   | clean |
| 32  | 22/22  | 22/22   | clean |
| 33  | 22/22  | 22/22   | clean |
| 34  | 22/22  | 22/22   | clean |
| 35  | 21/22  | 22/22   | compose clean; native `hideKeyboard` flakes on this `google_apis` image — its tap→IME pre-condition doesn't fire (while `isKeyboardVisible`/`pressKey`/`inputText`/`eraseText` pass, and compose passes `hideKeyboard`). Known IME-timing class, native-only, not a Compose gap |
| 36  | ⚠ infra | ⚠ infra | ps16k arm64 image never reaches install-ready within 600s headless on this Apple-Silicon host (tried 180/300/600s + clean reinstall). Host/emulator limit, not a driver bug — runs in CI with a warm snapshot or x86_64 runner |

**Headline: zero compose-specific failures.** On every API the harness could boot, Compose passes
everything native passes — and never less. There is **no command that passes on native but fails on
compose, on any API.** The only Tier-A deltas anywhere are environmental and never in Compose's
disfavour: `killApp` on 24–27 (Android `am kill` platform limitation, identical on both frameworks)
and `hideKeyboard` on api35-native only (a `google_apis` IME-timing flake — compose passes it).

Separately, the compose-only **`mergeDescendants`** behavior is a deliberate **failing** test
reproducing [#2704](https://github.com/mobile-dev-inc/Maestro/issues/2704); it is not counted in the
22-command parity total and is skipped on native.
