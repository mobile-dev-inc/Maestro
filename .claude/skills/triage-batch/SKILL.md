---
name: triage-batch
description: Use when a whole fidelity differential batch has finished and you need to
  triage its divergences — dispatching triage-one across the runs the harness bucketed
  as genuine-fidelity in classification.json. A thin orchestrator over a completed
  batch's classification.json; holds NO classification rules of its own. Use after
  remote-differential-batch's collect, before hand-triaging individual folders.
---

# Triage a batch of fidelity divergences

## Overview

**Thin orchestrator.** This skill holds NO classification logic. The harness already
classified every run into `classification.json` (one core function, single-run and
batch identical by construction). Your job is to read that file, take the runs worth
triaging, and dispatch `triage-one` on each — once per distinct surface signature.

**Core principle:** the harness bucketed; you dispatch. If you find yourself deciding
whether a run is a "real" divergence or a wall, STOP — that decision is already in
`classification.json.groups[].bucket`. Re-deriving it here is exactly the drift the
in-core classifier exists to prevent.

## Input

`classification.json` (from `remote-differential-batch`'s `collect`, or a single-run
`run_differential.py`). Shape:

```json
{
  "runs":   [{"runId","package","bucket","firstDivergence","signature"}],
  "groups": [{"signature","package","message","bucket","runIds"}]
}
```

`groups[]` already collapses runs sharing an identical surface signature
`(package, first-divergence message)`.

## What to dispatch — and what NEVER to

**Dispatch `triage-one` on `groups[]` where `bucket == "genuine-fidelity"`, one
dispatch per group.** The group's `runIds` are exact-duplicate runs by surface
signature (e.g. an app run twice); triage the first, note the rest as duplicates —
never one dispatch per run.

**NEVER dispatch — and never open a video for —** any group whose bucket is
`capability-gap`, `strategy-gap`, `env-mismatch`, or `none`. Those are walls, gaps,
and agreements: recorded, not triaged. Filtering them out is the whole point of
consuming the classifier instead of eyeballing `triage-folders.txt`.

## Surface signature, NOT root cause, is the dispatch key

Dedupe for dispatch is by **surface signature only** — it's already done in `groups[]`.

**Do NOT collapse by root cause before dispatch.** Two groups can share one root cause
yet carry different surface messages — wahed ("not actionable") and newcore ("not
visible") are one root cause (text-selector ambiguity) but two surface signatures, so
they are two groups and get two dispatches. That is correct, not waste: you cannot know
they share a cause until `triage-one` has analyzed each. **Root-cause grouping is a
post-triage REPORTING step, not a dispatch filter.**

## Concurrency: parallel analysis, serialized relaunch

- **Analysis is parallel.** `triage-one` is post-mortem-first — it works from artifacts
  alone. Dispatch the genuine-fidelity groups concurrently.
- **Relaunch is serialized.** When a `triage-one` chooses to relaunch
  (`--to-step N --keep-device`), it holds a device booted. Only ONE held device at a
  time — serialize any relaunch step across the dispatched jobs so two don't contend
  for the pool. Most single-verb divergences need no relaunch at all, so this rarely
  bottlenecks.

## Output — the batch report

After the dispatched `triage-one` jobs return, emit a batch report that:
- Lists each genuine-fidelity group: signature, runIds (with duplicates noted),
  verdict (device-core | layer-above | seam), the real failing verb, and the
  fixture-coverage state **with framework**.
- **Groups the verdicts by ROOT CAUSE** — this is the one place root-cause collapsing
  happens, and it is reporting only. (e.g. wahed + newcore → one root cause: text-
  selector ambiguity, two surface signatures.)
- Records, for auditability, the counts in every OTHER bucket (capability-gap /
  strategy-gap / env-mismatch / none) that were correctly NOT triaged.

## Red flags — STOP

- Dispatching once per `runId` instead of once per `group` → you're re-triaging
  duplicates.
- Deciding a run "looks like a wall" and skipping it, or "looks genuine" and adding it
  → the bucket already decided; don't re-classify.
- Collapsing wahed + newcore into one dispatch because "same cause" → different surface
  signatures dispatch separately; cause-grouping is post-triage.
- Opening a video for a `capability-gap` / `env-mismatch` group → never.
- Two relaunches holding devices at once → serialize relaunch.

## Open question (flagged for revisit)

Whether `triage-batch` should stay a skill or fold into the `remote-differential-batch`
operator step is unresolved (spec open question). Implemented as a skill for now;
revisit after the first triage eval.
