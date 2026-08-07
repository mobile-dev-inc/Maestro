# Mini-spec — device-core capabilities buildable in parallel with the harness

> **Shape: dispatchable menu.** device-core capability gaps that (a) block any real customer flow and
> (b) can be built **now, in parallel** with the validation harness — because they're internal to
> `maestro-device-core`, graded by its own conformance/tests, and depend on nothing in the Maestro
> integration. Written 2026-08-07. Status: draft. This is a *candidate list to inform* the command
> audit, not a replacement for it — final sequencing comes from "which flows do we want to unlock
> first." Work happens in `~/codes/maestro-device-core` (prototype-first while the contract is
> paused); each row maps to a ROADMAP capability.

## Why these can run in parallel

The harness spec (`2026-08-07-device-core-primary-runner-validation-harness-design.md`) builds the
*instrument*. These build the *capabilities the instrument measures*. They share no code: the harness
consumes device-core as a published jar, so capability work lands in device-core, gets graded by
device-core's conformance corpus on a real device, and shows up in the harness the next time the jar
is republished. So both tracks move at once; the only coupling is the jar version (pin it — harness
spec §7).

## The wall a real flow hits first

Ordered by "unblocks the most flows, earliest" — smallest/highest-leverage first. Each row: current
state (cited at `main`), the gap, why a real flow needs it, rough size.

### 1. Android `tap` by Text  ·  ROADMAP `tap` (S1)  ·  **small, highest leverage**
- **Today:** `inspect()` resolves `Selector.Id` **and** `Selector.Text`, but `tap`'s resolver
  (`shared/gate/android/Resolver.kt:159-164`, via `UiAutomationTapStrategy`) resolves **`Id` only** —
  `getByText(...).tap()` throws `NotImplementedError`.
- **Gap:** wire the already-working text resolver into the tap path.
- **Why:** real flows tap by visible text constantly; a translated flow dies at its *first* text tap
  today. This is the single biggest immediate unblock, and the resolution half already exists.

### 2. `launchApp` / `clearState`  ·  ROADMAP S5 (`listed`)  ·  **small–medium**
- **Today:** unbuilt (`Device.launchApp` / `Device.clearState` are `listed`, no strategy).
- **Gap:** control-plane acts over adb/simctl (`am start` / `pm clear`; simctl launch/terminate +
  data reset). Not UiAutomation-slot work, so relatively self-contained.
- **Why:** every real flow *starts* with launch + known-state reset. The ROADMAP defers these to S5,
  but a runnable flow needs them on step one — this is the "pull S5 setup forward" note from the
  harness spec.

### 3. `screenshot`  ·  ROADMAP S4 (`researching`)  ·  **small–medium, double-duty**
- **Today:** `researching`, unbuilt.
- **Gap:** a screen capture off the existing drivers.
- **Why:** doubles as the harness observability prerequisite (harness spec §5) — a device-core-owned
  failure is untriageable without it. Building it advances *both* tracks, so it punches above its
  weight.

### 4. `inputText`  ·  ROADMAP `inputText` (S2, `listed`)  ·  **medium–large**
- **Today:** unbuilt.
- **Gap:** the input-strategy fork (IME / keys / paste), where S2's fidelity learning lives.
- **Why:** any login / form / search flow needs text entry. Larger because "deterministic,
  velocity-killed input" is a real design question, not a wrapper.

### 5. `setPermission`  ·  ROADMAP S5 (`listed`)  ·  **small**
- **Today:** unbuilt.
- **Gap:** grant/deny via adb `pm grant` / simctl privacy.
- **Why:** permission dialogs block real flows early; cheap to add alongside launch/clearState.

### 6. `scrollTo` + first settle signal  ·  ROADMAP S3 (`listed`)  ·  **large, the deep one**
- **Today:** unbuilt; `settle` is always null, `delivered` = "accepted for dispatch," not "landed."
- **Gap:** off-screen reach *and* the first real synchronization primitive (end-of-content signal,
  stillness).
- **Why:** needed for any list/scroll flow — but note settle *fidelity* is a genuine unknown the
  harness is built to probe (harness spec, unknowns #1/#3). Build a first `scrollTo` + settle here;
  expect the harness to tell you where it's not good enough. Sequence this *after* the cheap unblocks
  so the harness has something to measure it against.

### 7. iOS `tap`  ·  ROADMAP `tap` (S1)  ·  **medium–large, platform-gated**
- **Today:** an unbuilt throwing stub (`api/adaptors/ios/IosLocator.kt:35`); iOS `inspect` does
  `Text` + `Nth`-of-`Text`.
- **Gap:** the iOS inject path (XCTest).
- **Why:** only on the critical path **if iOS is the validation platform** — Android is more built
  today, so this is gated on the platform-first decision (harness spec open questions).

## Dispatch notes

- **Grade on a device, not just green tests.** device-core's guardrail is real-device confirmation
  via its conformance corpus; a capability isn't done because unit tests pass. Use the
  `run-spike-experiment` host pool (`android_agents` for the Android rows).
- **Roadmap honesty.** Several S1 rows are `implemented` *ahead of evidence* (`tap` carries
  `spike: owed:tap-injection-and-landing`). Building #1 above extends that same ahead-of-evidence
  posture — fine under guardrail 1, but the `owed:` spike debt is real and should ride the row.
- **Independence caveat.** #1 (Android tap-by-Text) and #3 (screenshot) are the two lowest-risk,
  highest-leverage starts and touch nothing the harness refactor touches — safe to dispatch
  immediately, before the harness lands.
- **Final order is yours.** This is the buildable-in-parallel menu; the flows you most want to unlock
  decide the actual sequence.
