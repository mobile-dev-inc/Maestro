---
name: triage-3x-divergence
description: Use when a flow passes on 2.x Maestro but fails or diverges on the 3.x
  device-core candidate, and you need to know whether the fault is in device-core or the
  layer above (Orchestra / DeviceGateway). Triages the divergence and emits a minimal
  reproduction. Triage only — do not fix.
---

# Triage a 2.x-vs-3.x fidelity divergence

## INPUT
- One or more flows that PASS on 2.x and FAIL/diverge on 3.x.
- The harness run output for each: `diff.json`, `2x/steps.jsonl`, `3x/steps.jsonl`,
  `2x/screen.mp4`, `3x/screen.mp4`, `2x/.../maestro.log`, `3x/.../maestro.log`.
- The flow SOURCE: the `.yaml` under the run's `workspace/flows/`, INCLUDING every
  `runFlow:` sub-flow it pulls in (`utils/*.yaml`, `express_login.yaml`, …).
- The pinned `devicecore.version` under test, and source access to `maestro-orchestra`
  (Orchestra + DeviceGateway) and `maestro-device-core` at that version.

## OUTPUT
- **Verdict:** device-core | layer-above | seam.
- The exact failing step (first `FAIL`, not first `DIVERGE`) + failure mode.
- Minimal reproduction (an adb/shell command or a 2-line flow) for Android or iOS.
- The mechanism: which strategy/method, at which pinned version, and why it fails.
- A reproducible **fixture-app scenario, staged on the SAME framework as the customer's
  screen** (android-compose / android-rn / android-views / …), that turns the divergence into
  a failing (RED) conformance case — the artifact that lets the fix be red→green tested. Frame
  it as a phenomenon per the device-core `fixture-apps` skill; do not stage a verdict.

## STEPS

0. **Validate the run is real before triaging.** A divergence number is worthless if the
   pipeline didn't produce comparable traces. Confirm: both sides' `steps.jsonl` are
   non-empty; both `screen.mp4` exist; no mass provisioning error in the 3x log
   (`never became reachable`, `ResidenceProvisioner`). A UNIFORMLY low agree-rate across
   ALL flows is a pipeline/environment smell (wrong 2x oracle binary, stale device
   instrumentation, hard-stop cascade), NOT N independent bugs — fix the pipeline and re-run
   before reading a single diff.

1. **Read the flow source and map steps → commands FIRST.** Open the flow `.yaml` and expand
   every `runFlow:` sub-flow inline. Then map each trace `stepIndex` to its concrete command.
   You cannot diagnose "step 13 SwipeCommand" without knowing it is the `swipe DOWN` that sits
   right after `tapOn "Continue"` inside `express_login.yaml`. Do this before video or theory.

2. **Find where the divergence ACTUALLY starts — from the frames, not the harness.** The
   harness flags a step (first `FAIL`, else first non-AGREE — a short 3x `steps.jsonl` vs 2x
   means the flow HARD-STOPPED there, and every later `DIVERGE` is cascade). Treat that step as
   a POINTER, not the origin: the screen usually went wrong SEVERAL STEPS EARLIER — a swipe
   that dismissed a sheet, a load that never finished — while later steps still reported PASS.
   Back UP through `3x/screen.mp4`, comparing to `2x` at each command, to the EARLIEST frame
   where the two screens diverge; that frame + the act just before it is the real break. Tile a
   montage (no `drawtext` — often not compiled in):
   `ffmpeg -ss <t> -i 3x/screen.mp4 -vf "fps=3,scale=220:-1,tile=6x4" -frames:v 1 out.png`,
   correlating frames→steps via video duration and which steps completed.

3. **Ground-truth the screen.** Reproduce that screen. Android: `adb shell uiautomator dump`
   — is the target element present, with what attributes (class, clickable, bounds,
   text/contentDescription)? iOS: the accessibility hierarchy. Read the 2x `maestro.log` — it
   logs the exact element 2.x matched.
4. **Attribute the layer** (read both sides of the seam):
   - Did Orchestra/DeviceGateway pass the correct selector AND timeout into device-core?
     (check the code + the 3x log's command metadata). Dropped/malformed input → **layer above**.
   - Given correct input, did device-core behave right? Check the 3x log timing (did it honor
     the budget), the `Resolution`/`Outcome` it got, and the device-core strategy source for
     that verb. Wrong behavior on correct input → **device-core**.
5. **Minimize the repro.** Reduce to the smallest deterministic artifact that shows
   2x-works / 3x-fails (one adb/shell command, or a 2-line flow). Run it both ways to confirm.
6. **Write the report:** verdict + failing step + evidence chain + mechanism (file:method) +
   minimal repro + fix owner. Do not implement the fix.

## GUARDRAIL
A step verdict of PASS is NOT proof the app did the thing (e.g. `am start -W` prints ok for
the wrong activity while the app never foregrounds). Always corroborate a verdict with the
video or a device dump.

Before blaming device-core, rule out flake — adb-server wedge, cold-start race, a concurrent
session/emulator contending — with ONE clean re-run. A hang or `reach=0`/`incomplete` is
usually environment, not a fidelity bug.
