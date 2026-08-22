# Phase 5 — device-core fidelity framework

## What this is

The program's whole point is to answer one question about the new device-core
backend: **where does it agree with maestro, and where does it not?** Phase 5 is
the framework that answers it — and keeps answering it as device-core grows.

Given device-core's current verb set (launchApp, assertVisible/notVisible on
exact literal text, tapOn by literal id), no real corpus flow runs end-to-end on
it — every real flow leans on inputText, tap-by-text, scroll, or setLocation,
which device-core declines. So the framework is proven on a manufactured,
deterministic flow against the emulator's built-in Settings app, and it's built
to widen automatically: as device-core implements more verbs, the same run
reports agreement at greater depth. The framework doesn't change — only the
numbers do.

## How it works

`phase5_fidelity.py` runs ONE flow twice on a shared emulator:

- **legacy** (no env var) — maestro's proven driver. The oracle.
- **device-core** (`MAESTRO_DEVICECORE_ASSERT=1`) — device-core solo.

It pulls both per-step traces and, aligning by step index, classifies each step:

| Status  | Meaning |
|---------|---------|
| AGREE   | device-core ran the verb and produced maestro's verdict + chosen element (verdict exact, element identity exact, coords within tol) |
| DIVERGE | device-core ran the verb but disagreed with legacy — a real fidelity failure |
| OWED    | device-core declined the verb (not implemented yet) — a coverage gap, not a divergence |
| MISSING | device-core aborted upstream and never reached this step |

The comparison core is `diff_traces.diff_flow` (the same trusted engine as the
Phase 2 zero-divergence gate): verdict exact, element identity (text +
resourceId) exact, coordinates within ±2px, `declined` steps treated as coverage
gaps. Because the Settings flow is deterministic, a single legacy vs single
device-core pass is clean — no quad control needed (that machinery, in
`run_gate.py`, exists for the non-deterministic corpus).

Run it:

```
python3 phase5_fidelity.py --flow flows/settings-fidelity.yaml --appid com.android.settings
```

## The demonstration

`flows/settings-fidelity.yaml`: `launchApp` → `assertVisible` ×3 (Notifications,
Battery, Connected devices — exact, unambiguous, above-the-fold Settings-home
text) → `assertNotVisible`. Result (`phase5-fidelity/fidelity-report.json`):

```
device-core ran 4/7 steps   served=4 (agree=3 diverge=1)   owed=0   missing=3
  0 AGREE    DefineVariablesCommand     legacy=PASS devicecore=PASS
  1 AGREE    ApplyConfigurationCommand  legacy=PASS devicecore=PASS
  2 AGREE    LaunchAppCommand           legacy=PASS devicecore=PASS
  3 DIVERGE  AssertConditionCommand     legacy=PASS devicecore=FAIL
  4 MISSING  AssertConditionCommand     legacy=PASS devicecore=None
  5 MISSING  AssertConditionCommand     legacy=PASS devicecore=None
  6 MISSING  AssertConditionCommand     legacy=PASS devicecore=None
```

device-core launches the app (step 2 AGREE), then FAILs the first assert where
legacy passes — and the flow aborts.

## What the divergence is (and isn't)

The framework surfaced a real gap; an isolation run localized it. With Settings
**pre-launched and settled** (via adb) and device-core run on an asserts-only
flow (`flows/settings-asserts-only.yaml`), device-core passes **every** assert —
Notifications / Battery / Connected devices visible, the nonsense string
not-visible — matching legacy exactly.

So device-core's assertVisible/notVisible verdict — derived from device-core's
native `actionability.visible` (task 4.D1) — is **faithful to maestro** when the
app is actually up. The full-flow divergence is entirely `launchApp`: device-core's
current `launchApp` reports success when the platform launch command returns, not
when the app reaches the foreground (its own KDoc defers the foreground
postcondition; it's `implemented-quick` on the device-core ROADMAP). The
following assert races the launch. That's a device-core `launchApp` issue, not a
verdict-logic issue — exactly the kind of precise, actionable signal the
framework is for.

## Reading the result

- **device-core agrees with maestro** on launchApp (as launched) and on
  assertVisible/notVisible verdicts against a stable screen.
- **The one divergence** is `launchApp`'s missing foreground-settle — fixable in
  device-core; until then, an assert immediately after launch will race it.
- **Owed verbs** (inputText, tap-by-text, scroll, tapOnPoint, setLocation) show
  up as OWED coverage gaps the moment a flow uses them — that's the list of what
  device-core can't yet be measured on.

As device-core lands foreground-settle and more verbs, re-running this framework
(on richer flows) turns OWED into SERVED and DIVERGE into AGREE, and the
agreement number climbs — with no change to the framework itself.
