# diff_traces.py — the differential engine

Compares two per-step trace files (`steps.jsonl`) produced by two backends
running the same Maestro flow, and reports where they diverge. This is the
pass/fail engine behind the zero-divergence gate: legacy execution backend
vs. the stock/device-core backend, step by step, on the same flow.

Pure Python 3, stdlib only. No device, no network, no external deps.

## Input

Each trace file is JSONL — one JSON object per line, one line per step,
schema from Task 3.1/3.1b:

```
{stepIndex, backendId, command:{type, selectorText?, selectorId?},
 verdict:"PASS"|"FAIL"|"ERROR",
 chosenElement?:{x,y,width,height,centerX,centerY,text?,resourceId?,index?},
 declined?:bool, declinedReason?}
```

`chosenElement` is absent when the command resolved no element (e.g.
`LaunchAppCommand`). `declined` is set only by the device-core backend when
it deliberately opts out of a step it doesn't support yet.

## Comparison rules

Steps are aligned by `stepIndex`, not by line position.

- **verdict** — must be exactly equal. Any mismatch is a `verdict`
  divergence.
- **chosenElement presence** — both absent is fine. One present and one
  absent is an `element-presence` divergence.
- **chosenElement identity** — `text` and `resourceId` must be exactly
  equal. Any mismatch is an `element-identity` divergence. This is checked
  before coordinates, and short-circuits them: if the backends picked
  different elements, comparing their pixel positions is meaningless.
- **coordinates** — `x`, `y`, `width`, `height`, `centerX`, `centerY` are
  REQUIRED fields on `chosenElement` (unlike optional `text`/`resourceId`).
  Each may differ by at most `--tol` pixels (default **2**); beyond that,
  one `coordinate` divergence for the step (not one per field). A missing
  or `null` value for any of these fields on either side is *also* a
  `coordinate` divergence — it is never skipped or tolerated, because a
  backend dropping a required field (a serialization bug) is exactly the
  kind of regression this tool needs to catch, not silently pass as
  "nothing to compare."
- **declined** — if either side has `declined:true` for a step, that step
  is logged as a coverage gap (`{stepIndex, backend, command}`), not
  compared, and NOT counted as a divergence. A backend choosing not to
  attempt a step is a known gap in what's been ported, not evidence the
  two backends disagree.
- **step count / alignment** — if a `stepIndex` exists on only one side
  (e.g. A ran 5 steps, B ran 4), that index is a `step-count` divergence.
  The rest of the steps that do line up are still compared normally.
- **first divergent step** — the lowest `stepIndex` carrying any
  divergence, or `null` if there are none.

### Why ±2px

Two independent resolution paths — the legacy backend's view-hierarchy walk
and device-core's element lookup — can round element bounds slightly
differently (float-to-int truncation, DP-to-px conversion, differing
hierarchy traversal order for overlapping nodes) without disagreeing about
*which* element they picked or whether the step passed. A couple of pixels
of float rounding is not a regression; a backend tapping the wrong element,
or 20px off because it picked the wrong node, is. `--tol 2` catches the
latter while tolerating the former. Verdict and element identity get no
tolerance at all — those are supposed to be identical, and any difference
there is exactly what this tool exists to catch.

## Usage

### Single flow

```
python3 diff_traces.py --a legacy/steps.jsonl --b stock/steps.jsonl --json
```

Optional `--tol N` overrides the pixel tolerance (default 2).

Prints the per-flow result:

```json
{
  "stepsCompared": 2,
  "divergences": [],
  "firstDivergentStep": null,
  "coverageGaps": []
}
```

**Exit code: 0 iff `divergences` is empty. Non-zero otherwise.** This is
the contract the gate scripts key off — don't change it without updating
every caller.

### Corpus (aggregate gate verdict)

```
python3 diff_traces.py --corpus corpus-dir --json
```

`corpus-dir` holds one subdirectory per flow, each with `a/steps.jsonl` and
`b/steps.jsonl`. Prints the corpus summary:

```json
{
  "totalFlows": 2,
  "flowsZeroDivergence": 1,
  "flowsWithDivergence": 1,
  "divergenceCountsByKind": {
    "element-identity": 1
  },
  "coverageGapCountsByCommand": {
    "TapOnElementCommand": 1
  },
  "flows": [ ... ]
}
```

(`flows` carries the full per-flow result for each flow, for debugging —
the gate itself should only need the summary counts and the exit code.)

**Exit code: 0 iff every flow has zero divergences. Non-zero otherwise.**
This is the gate verdict.

### Errors

A missing trace file, an empty trace file, or a line that isn't valid JSON
is a hard error: a message on stderr and a non-zero exit (2). It is never
treated as "zero divergences" — a gate that silently passes on a missing
file is worse than no gate at all.

## Tests

```
pytest validation-harness/test_diff_traces.py -v
```

The tests drive `diff_traces.py` as a subprocess (not by importing
internals), so they lock in the actual CLI/exit-code contract, not just the
comparison logic.
