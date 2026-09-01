---
name: triage-one
description: Use when triaging ONE genuine-fidelity divergence from the 2.x-vs-3.x
  device-core fidelity harness — a run whose classification.json bucket is
  genuine-fidelity, where a flow passes on 2.x but the candidate diverges. Decides
  whether the fault is device-core, the layer above (Orchestra / DeviceGateway), or
  the seam, and emits a verdict + evidence chain + minimal repro + RED conformance
  fixture case. Triage only — do not fix. Never triage a wall/gap.
---

# Triage one genuine-fidelity divergence

## Overview

**Post-mortem first.** Everything you need is usually in the run's artifacts —
`diff.json`, both sides' `steps.jsonl`, both `maestro.log`, both `screen.mp4`, the
`chosenElement` records. You do NOT need a live device to reach a verdict. Reserve
the live relaunch (`--to-step N --keep-device`) for the cases that genuinely need
current screen state.

**Core principle:** the harness already ran the differential. A single-verb failure
means `diff.json` IS the both-ways answer (2x PASS / 3x FAIL) for that verb — a fresh
2-line repro only re-derives it. Spend your effort attributing the layer and naming
the fixture gap, not re-running what the batch already proved.

## When to use — and when NOT

Triage a run **only** when its `classification.json` bucket is `genuine-fidelity`.

**NEVER triage — and never open a video for —** a `capability-gap` (an unimplemented
verb walling as `OWED` / `WALL_PROPAGATED`), a `strategy-gap`, or an `env-mismatch`.
Those are recorded, not divergences. The classic by-eye mistake is reading
`assertNotVisible` / `waitFor(GONE)` on an unimplemented verb as a fidelity
divergence — it is a `diverge=0` wall. The classifier exists to stop exactly this;
trust the bucket.

**First-real-divergence excludes walls.** The step you triage is the first step whose
`status == "DIVERGE"` — never an `OWED` / `WALL_PROPAGATED` / `NOT_REACHED` step.

## INPUT (per run, from the harness output tree)

- `diff.json` — the fidelity report; `firstDivergence` from `classification.json`.
- `2x/steps.jsonl`, `3x/steps.jsonl` — the per-step traces incl. each verb's
  `chosenElement` (bounds + text + resourceId).
- `2x/maestro.log`, `3x/maestro.log` — the CLI logs, **now always captured** (harness
  pulls both sides). Older `batch-out-full` runs predate log capture — see archaeology
  fallback below.
- `2x/screen.mp4`, `3x/screen.mp4` — the screen recordings.
- The flow SOURCE — the run's `flow/` copy (expanded yaml + `runFlow:` subflows) when
  present, else the `.yaml` under `workspace/flows/`.
- Source access to `maestro-orchestra` (Orchestra + DeviceGateway) and device-core at
  the pinned effective version.

## OUTPUT

- **Verdict:** device-core | layer-above | seam.
- The real failing verb (see "first FAIL, not the wrapper, and often not the assert")
  + failure mode.
- Evidence chain: which strategy/method, at which pinned version, and why it diverges.
- Minimal repro (an adb/shell command or a 2-line flow) — only when it adds signal
  beyond `diff.json`.
- A RED conformance fixture case **staged on the SAME framework as the customer's
  screen**, framed as a phenomenon per the device-core `fixture-apps` skill. See the
  fixture-coverage step — always name the framework.

## The two divergence shapes (learn to tell them apart FIRST)

Every genuine-fidelity divergence is one of these two shapes. The triage move differs,
so classify the shape before diving in.

**(a) Frames agree, semantics differ.** The 2x and 3x videos show pixel-identical
screens through the failing step, yet the selector resolves differently. The fault is
in the **selector-resolution strategy on an identical screen** — go straight to the
resolver. Identical frames are NOT a dead end; they are the diagnosis. (wahed:
`.*Welcome.*` not actionable; newcore: "Get Started" not visible.)

**(b) Frames diverge upstream at a PASSED verb, cascading to a downstream assert.**
The screens diverge several steps BEFORE the flagged failure, at a verb that reported
PASS on both sides. A corrupted earlier action left the app on the wrong screen, and a
later assert cascade-failed. The fault is the **earlier passed verb**, not the assert —
walk the frames back to it. (plum: `inputText` at step 20 passed both sides; the FAIL
was a downstream `assertVisible "Enter your code"` at step 22 because the input was
corrupted and the app never navigated.)

## STEPS

**0. Validate the run is real before triaging.** Both sides' `steps.jsonl` non-empty;
both `screen.mp4` exist; no mass provisioning error in the 3x log (`never became
reachable`, `ResidenceProvisioner`). A uniformly low agree-rate across ALL flows is a
pipeline/environment smell, not N bugs — fix the pipeline and re-run first.

**1. Anchor on the classified first divergence, then find the REAL failing verb.**
`classification.json.firstDivergence` gives you the first `DIVERGE` step. Two traps:

- **The `firstDivergentStep` is often a `runFlow` wrapper.** `steps.jsonl` is written
  in *completion* order, so a wrapper's low index completes AFTER its nested leaf. The
  real leaf is usually a higher-indexed nested step. Keep "first FAIL, not first
  DIVERGE" — but point at the nested leaf, not the wrapper. Warn on this explicitly in
  the report.
- **The origin verb may have PASSED on BOTH sides.** The "first FAIL" heuristic
  structurally cannot point at a verb that passes its own verdict (shape b). So the
  **video-walk-back is primary, not optional**: walk `3x/screen.mp4` back against `2x`
  to the earliest frame where the screens diverge, and identify the last verb that
  PASSED before that frame. That verb — not the failing assert — is the origin.

**2. Recover the sub-cause from the log (or, for logless runs, by archaeology).**
A mapped message like "Element not actionable" hides its sub-cause — `Outcome.Blocked`
can be ambiguous-match vs gate-closed vs Unavailable. The 3x `maestro.log` (now always
captured) records which; read it first. For the older logless corpus (`batch-out-full`
predates log capture), recover the sub-cause by archaeology: (a) did an adjacent
selector Act on the same node? and (b) enumerate the strategy's failure producers in
device-core source and match the observed symptom to one. State plainly that a captured
log would make this trivial.

**3. Ground-truth the screen — 2x `chosenElement` FIRST.** The 2x trace already records
the exact element 2.x matched: bounds + text + resourceId, free, no device needed. Use
it as ground truth. A live `uiautomator dump` (Android) / accessibility hierarchy (iOS)
is the FALLBACK — it is expensive behind a customer login. **Warn:** a 3x
`chosenElement` with zero bounds / no text is a serialization artifact, not evidence of
a resolution problem — do not read it as "3x resolved to nothing."

**4. Attribute the layer (read both sides of the seam).**
- Did Orchestra / DeviceGateway pass the correct selector AND timeout into device-core?
  (check the code + the 3x log's command metadata). Dropped/malformed input →
  **layer above**.
- Given correct input, did device-core behave right? Check the 3x log timing (did it
  honor the budget), the `Resolution` / `Outcome` it got, and the device-core strategy
  source for that verb. Wrong behavior on correct input → **device-core**.
- Fault straddles the boundary (correct input, correct strategy, wrong glue) → **seam**.

**5. Check the fixture coverage — and ALWAYS name the framework.** Before proposing a
RED fixture, determine the framework the customer screen used (android-compose /
android-rn / android-views / ios-swiftui / ios-uikit / ios-flutter / ios-rn /
ios-webview) — infer it from the artifacts (RN via `libhermes` / JS bundle in the APK;
Views via resource-ids; Compose via semantics tree shape). If you cannot determine it
from artifacts, **say so — do not guess.** Then search the device-core conformance
cases (`~/codes/screen-settle-capability/conformance/cases/**`, `conformance/cases/Cases.kt`;
cases are per-verb, per-framework files) for the phenomenon and report EXACTLY ONE of:
  - **(i) not covered at all** — the phenomenon has no case.
  - **(ii) covered, but NOT for the framework that regressed** — the common case. A
    fixture green on one framework says nothing about another. (Validated: wahed → no
    android-rn tap/ambiguity case; newcore → text-aggregation ambiguity existed only as
    a Compose ID-collision case, not RN; plum → no android-views long-input case,
    inputText has Compose cases only.)
  - **(iii) covered for the regressed framework** — then the divergence is a regression
    against an existing GREEN conformance case: a stronger, differently-owned signal.

  The recommendation MUST state the framework. Stage the RED case as a phenomenon per
  the `fixture-apps` skill; do not stage a verdict.

**6. Minimize the repro — only if it adds signal.** A single-verb failure needs no live
re-run: `diff.json` already is the 2x-PASS / 3x-FAIL differential. Write a fresh repro
only for multi-step / screen-state cases where the artifacts alone don't isolate the
verb. When you do, reduce to the smallest deterministic artifact (one adb/shell command
or a 2-line flow) and run it both ways.

**7. Write the report:** verdict + real failing verb + evidence chain + mechanism
(file:method at the pinned version) + minimal repro (if any) + fixture-coverage state
with framework + fix owner. Do not implement the fix.

## Relaunch (only when you need live screen state)

Use the harness's hold-open mode — never hand-roll emulator/adb/replay:

```
python3 run_differential.py <folder> --to-step N --keep-device --side 3x
```

It reuses the run unit's boot/stage/install/run path, truncates the flow to the first
N **top-level** commands, runs one side, holds the device booted, and prints
`HELD_DEVICE serial=<id>` for a manual `uiautomator dump`.

**Caveat (the harness warns too):** `--to-step N` truncates TOP-LEVEL commands, but
trace `stepIndex` is completion order including nested `runFlow` / `repeat` children —
so top-level command N is not always trace-`stepIndex` N. For a divergence inside a
subflow, truncate to the enclosing top-level `runFlow:` that contains it.

## GUARDRAILS

- A step verdict of PASS is NOT proof the app did the thing (`am start -W` prints ok for
  the wrong activity while the app never foregrounds). Corroborate every verdict with
  the video or a device dump.
- Before blaming device-core, rule out flake — adb-server wedge, cold-start race, a
  concurrent session — with ONE clean re-run. A hang or `reach=0` / `incomplete` is
  usually environment, not a fidelity bug.

## Red flags — STOP

- About to open a video for a `capability-gap` / `strategy-gap` / `env-mismatch` run →
  it is a wall, not a divergence. Only `genuine-fidelity` is triaged.
- Blaming the failing `assertVisible` → check whether the origin verb PASSED both sides
  upstream (shape b). Walk the frames back.
- Pointing at the `runFlow` wrapper index → find the higher-indexed nested leaf.
- Reading a 3x `chosenElement` with zero bounds as "resolved to nothing" → it's a
  serialization artifact.
- Recommending a fixture without naming the framework → a fixture green on another
  framework proves nothing about the regressed one.
- Writing a 2-line repro for a single-verb failure → `diff.json` already is that repro.
