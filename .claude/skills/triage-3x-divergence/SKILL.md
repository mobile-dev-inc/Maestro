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
- The harness run output for each: `diff.json`, `2x/screen.mp4`, `3x/screen.mp4`,
  `2x/.../maestro.log`, `3x/.../maestro.log`.
- Source access: `maestro-orchestra` (Orchestra + DeviceGateway) and `maestro-device-core`.

## OUTPUT
- **Verdict:** device-core | layer-above | seam.
- The exact failing step + failure mode.
- Minimal reproduction (an adb/shell command or a 2-line flow) for Android or iOS.
- The mechanism: which strategy/method, and why it fails.

## STEPS
1. **Find the break.** Read `diff.json`; take the first non-AGREE step. Record command,
   status (`DIVERGE`/`OWED`/`NOT_REACHED`), `errorType`, `errorMessage`.
   - `OWED` → verb not built in device-core, or not overridden in `RealDeviceGateway`.
     Check the gateway; usually done here.
   - `DIVERGE`/error → continue.
2. **Watch the video.** Pull frames from `3x/screen.mp4` at that step
   (`ffmpeg -ss <t> -frames:v 1 -vf scale=360:-1 out.png`) and compare to `2x`. Confirm
   what is ACTUALLY on screen — do not trust the PASS/verdict. If the screen is wrong, back
   up to the earliest step whose 3x screen differs from 2x (a failed launch fails every
   later step).
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
