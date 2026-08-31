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

Steps are aligned by `stepIndex`, not by line position, and not by
`backendId` — `--a` is the reference/oracle side (2.x legacy), `--b` is the
candidate side (device-core/3.x) throughout.

- **verdict** — must be exactly equal. Any mismatch is a `verdict`
  divergence.
- **chosenElement presence** — both absent is fine. One present and one
  absent is an `element-presence` divergence.
- **chosenElement identity** — `text` and `resourceId` are compared only
  where **both** sides provide a non-null value for that field; a field
  present on only one side is compatible, not a divergence (device-core
  never emits `resourceId` on `chosenElement`, so a `resourceId` on the 2.x
  side alone is expected, not a regression). Any mismatch where both sides
  *do* provide the field is an `element-identity` divergence. This is
  checked before coordinates, and short-circuits them: if the backends
  picked different elements, comparing their pixel positions is
  meaningless.
- **coordinates** — `x`, `y`, `width`, `height`, `centerX`, `centerY` are
  REQUIRED fields on `chosenElement` (unlike optional `text`/`resourceId`).
  Each may differ by at most `--tol` pixels (default **2**); beyond that,
  one `coordinate` divergence for the step (not one per field). A missing
  or `null` value for any of these fields on either side is *also* a
  `coordinate` divergence — it is never skipped or tolerated, because a
  backend dropping a required field (a serialization bug) is exactly the
  kind of regression this tool needs to catch, not silently pass as
  "nothing to compare." **Exception:** device-core's `chosenElement`
  zeroes ALL of `x`/`y`/`width`/`height`/`centerX`/`centerY` when it emits
  one (it only ever fills in `text`). When either side's bounds are all
  zero, coordinates are not comparable at all and are skipped entirely —
  the step's identity fields carry the comparison instead.
- **declined** — if either side has `declined:true` for a step, that step
  is logged as a coverage gap (`{stepIndex, backend, command}`), not
  compared, and NOT counted as a divergence. A backend choosing not to
  attempt a step is a known gap in what's been ported, not evidence the
  two backends disagree.
- **step count / alignment** — if a `stepIndex` exists on only one side
  (e.g. A ran 5 steps, B ran 4), that index is normally a `step-count`
  divergence. The rest of the steps that do line up are still compared
  normally. **Exception:** see OWED / NOT_REACHED below — a one-sided
  index past B's `NotImplemented` wall is a `notReached` entry, not a
  `step-count` divergence.
- **first divergent step** — the lowest `stepIndex` carrying any
  divergence, or `null` if there are none.

### OWED / NOT_REACHED — the device-core prefix wall

The 3.x (device-core) candidate trace is, by design, a PREFIX of the 2.x
oracle trace: it hard-stops the first time it hits a device verb it hasn't
built yet, emitting `error:{type:"NotImplemented", message}` on that step
instead of running it. `diff_flow` recognizes this pattern so the wall and
everything past it are never painted as divergences:

- `wallIndices` — every `stepIndex` in B whose `error.type ==
  "NotImplemented"`, leaf AND propagated. A composite (`repeat:`/`retry:`/
  `runFlow:`) that walls records `NotImplemented` on BOTH the walling leaf
  step AND its ancestor wrapper step(s), at different stepIndexes. ALL of
  them are excluded from divergence comparison — a wrapper carrying a
  propagated wall must never be diff-compared against the oracle's `PASS` and
  painted a divergence.
- `propagatedWallIndices` — the flow-control subset of `wallIndices` (command
  type `RunFlowCommand`/`RepeatCommand`/`RetryCommand`). These sit above the
  device seam, so their `NotImplemented` is never a genuine device wall — it
  is the leaf's wall propagating up as the exception unwinds. `fidelity.py`
  classifies them `WALL_PROPAGATED` (not `OWED`).
- `leafWallIndex` / `owedIndex` — the single leaf device wall: the deepest
  `stepIndex` in B whose `error.type == "NotImplemented"` AND whose command
  is NOT flow-control. This is the actual device verb that threw (the `OWED`
  verb) and the boundary past which the oracle kept going. `null` if B never
  walls on a non-flow-control verb. (The leaf is always the deepest wall: it
  hard-stops the flow the instant it throws, so its wrapper ancestors, whose
  indexes were assigned when they *started*, only record their propagated
  wall afterwards, at lower indexes.)
- Any oracle-only `stepIndex` beyond `owedIndex` (A has it, B doesn't) is
  recorded in `notReached` (`{stepIndex, command}`) instead of a
  `step-count` divergence — it's the un-reached oracle tail, not evidence
  the two backends disagree.
- A one-sided index that is NOT past a recorded wall (e.g. B has fewer
  steps than A for a reason other than a `NotImplemented` error) still
  falls back to the ordinary `step-count` divergence — the OWED/NOT_REACHED
  carve-out only applies to the specific device-core prefix-stop pattern.

Consumers built on top of `diff_flow` (see `fidelity.py`) use `owedIndex`,
`wallIndices`, and `notReached` to classify every oracle step into one of
five statuses: `AGREE` (reached on both sides, matched), `DIVERGE` (reached
on both sides, disagreed), `OWED` (the single leaf wall step), `WALL_PROPAGATED`
(a flow-control wrapper carrying the leaf's propagated wall — counted toward
none of the agree/diverge/owed/not-reached tallies), or `NOT_REACHED` (the
un-reached oracle tail past the leaf wall).

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
  "coverageGaps": [],
  "notReached": [],
  "owedIndex": null,
  "leafWallIndex": null,
  "wallIndices": [],
  "propagatedWallIndices": []
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
