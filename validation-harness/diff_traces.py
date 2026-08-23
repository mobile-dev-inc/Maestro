#!/usr/bin/env python3
"""diff_traces.py — the differential engine of the zero-divergence gate.

Compares two per-step trace files (steps.jsonl, one JSON object per line,
schema from Task 3.1/3.1b) produced by two backends running the same flow,
and reports per-step divergence in verdict, chosen-element identity, and
chosen-element coordinates. This is pure, local, stdlib-only Python: no
device, no network.

Exit-code contract (load-bearing — the gate scripts key off it):
  --a/--b mode:   exit 0 iff the flow has zero divergences.
  --corpus mode:  exit 0 iff EVERY flow in the corpus has zero divergences.
  Any input error (missing file, empty file, malformed JSON) is a non-zero
  exit with a message on stderr — never a silent pass.

See README.md for the full comparison-rule contract and the ±2px
tolerance rationale.
"""
import argparse
import json
import sys
from pathlib import Path

DEFAULT_TOL = 2

COORD_FIELDS = ("x", "y", "width", "height", "centerX", "centerY")
IDENTITY_FIELDS = ("text", "resourceId")

# Flow-control / composite command types — the ones that wrap sub-commands
# (Kotlin: the CompositeCommand sealed interface — RunFlowCommand,
# RepeatCommand, RetryCommand; confirmed against
# maestro-orchestra-models/.../Commands.kt and real composite-wall traces).
# These sit ABOVE the device seam: they never cross into device-core, so a
# NotImplemented recorded on one is never a genuine device wall — it is the
# leaf verb's NotImplemented PROPAGATING up through the wrapper as the
# exception unwinds. Such a wrapper step is excluded from divergence like any
# other wall, but it is NOT the OWED verb — only the leaf that actually threw
# is (see fidelity.py).
FLOW_CONTROL_TYPES = frozenset({"RunFlowCommand", "RepeatCommand", "RetryCommand"})


def is_flow_control(step):
    """True when `step`'s command.type is a flow-control/composite wrapper."""
    return (step.get("command") or {}).get("type") in FLOW_CONTROL_TYPES


class TraceError(Exception):
    """Raised for malformed/missing trace input. Always non-zero exit."""


def load_steps(path_str):
    """Load a steps.jsonl file into a dict keyed by stepIndex.

    Raises TraceError on any problem (missing file, empty file, malformed
    JSON line, missing required field) — never returns a partial/empty
    result silently.
    """
    path = Path(path_str)
    if not path.exists():
        raise TraceError(f"trace file not found: {path}")
    if not path.is_file():
        raise TraceError(f"trace path is not a file: {path}")

    text = path.read_text()
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise TraceError(f"trace file is empty: {path}")

    steps = {}
    for line_no, line in enumerate(lines, start=1):
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as e:
            raise TraceError(f"malformed JSON in {path} at line {line_no}: {e}") from e
        if "stepIndex" not in obj:
            raise TraceError(f"missing 'stepIndex' in {path} at line {line_no}")
        steps[obj["stepIndex"]] = obj
    return steps


def error_type(step):
    """Return step['error']['type'], or None if the step carries no error.

    OWED (the device-core wall) is identified by error.type=="NotImplemented"
    on the 3.x side — NOT by any error. A mutual FAIL (e.g. both sides raising
    an AssertionFailure) is a case where the sides AGREE the assertion failed,
    not an OWED step.
    """
    return (step.get("error") or {}).get("type")


def _bounds_zeroed(elem):
    """True when device-core's zeroed-bounds convention is in play.

    device-core's chosenElement zeroes ALL coords (x/y/width/height AND
    centerX/centerY) and emits only `text` (no resourceId). When either side
    is zeroed, coordinates are not comparable AT ALL — not even
    centerX/centerY, since those are zeroed too and therefore meaningless.
    """
    return (
        elem.get("x") == 0
        and elem.get("y") == 0
        and elem.get("width") == 0
        and elem.get("height") == 0
    )


def _coord_divergence(a_elem, b_elem, tol):
    """Return the first divergent coordinate field name, or None.

    x/y/width/height/centerX/centerY are REQUIRED fields on chosenElement
    (unlike optional text/resourceId) — a None/missing value here means a
    backend dropped a field it should always emit, which is itself a
    divergence, not something to tolerate. Skipping it would be a false
    green: a serialization bug that drops centerY would otherwise report
    zero coordinate divergence for the step.

    EXCEPTION: when either side's bounds are zeroed (device-core's
    zeroed-bounds convention — see _bounds_zeroed), coordinates are not
    comparable at all and this returns None unconditionally; identity
    (text/resourceId) is what carries the comparison for that step.
    """
    if _bounds_zeroed(a_elem) or _bounds_zeroed(b_elem):
        return None
    for field in COORD_FIELDS:
        a_val = a_elem.get(field)
        b_val = b_elem.get(field)
        if a_val is None or b_val is None:
            return field
        if abs(a_val - b_val) > tol:
            return field
    return None


def _identity_mismatch(a_elem, b_elem):
    """Return the first mismatched identity field name, or None.

    A field is only compared when BOTH sides carry a non-None value for it —
    device-core never emits `resourceId` on chosenElement, so a resourceId
    present on only one side (2.x has it, 3.x doesn't) is COMPATIBLE, not a
    divergence. Same rule for `text` (symmetric, though in practice both
    sides always carry it).
    """
    for field in IDENTITY_FIELDS:
        a_val = a_elem.get(field)
        b_val = b_elem.get(field)
        if a_val is not None and b_val is not None and a_val != b_val:
            return field
    return None


def diff_step(a_step, b_step, tol):
    """Compare one aligned pair of steps. Returns a list of divergence dicts
    (kind, a, b) — normally 0 or 1 entries, but presence/identity/coordinate
    are checked independently of verdict so a step can carry more than one.
    """
    divergences = []

    a_verdict = a_step.get("verdict")
    b_verdict = b_step.get("verdict")
    if a_verdict != b_verdict:
        divergences.append({"kind": "verdict", "a": a_verdict, "b": b_verdict})

    a_elem = a_step.get("chosenElement")
    b_elem = b_step.get("chosenElement")
    if (a_elem is None) != (b_elem is None):
        divergences.append({
            "kind": "element-presence",
            "a": a_elem is not None,
            "b": b_elem is not None,
        })
    elif a_elem is not None and b_elem is not None:
        identity_field = _identity_mismatch(a_elem, b_elem)
        if identity_field is not None:
            divergences.append({
                "kind": "element-identity",
                "a": {f: a_elem.get(f) for f in IDENTITY_FIELDS},
                "b": {f: b_elem.get(f) for f in IDENTITY_FIELDS},
            })
        else:
            coord_field = _coord_divergence(a_elem, b_elem, tol)
            if coord_field is not None:
                divergences.append({
                    "kind": "coordinate",
                    "a": {f: a_elem.get(f) for f in COORD_FIELDS},
                    "b": {f: b_elem.get(f) for f in COORD_FIELDS},
                })

    return divergences


def _coverage_gap(step_index, step, backend_id):
    command = step.get("command", {})
    return {
        "stepIndex": step_index,
        "backend": backend_id,
        "command": command.get("type"),
    }


def _not_reached_entry(step_index, a_step):
    command = (a_step or {}).get("command", {})
    return {
        "stepIndex": step_index,
        "command": command.get("type"),
    }


def _wall_indices(b_steps):
    """Every stepIndex in b (the 3.x candidate) whose error.type is
    "NotImplemented" — every device-core wall touched by this flow, leaf AND
    propagated. A composite (repeat:/retry:/runFlow:) that walls records
    NotImplemented on BOTH the walling leaf step and its ancestor composite
    step(s), at different stepIndexes. ALL of them must be excluded from
    divergence comparison — a wrapper carrying a propagated NotImplemented
    must never be diff-compared against the oracle's PASS and painted DIVERGE.
    (Only the leaf is OWED, though — see _leaf_wall_index / fidelity.py.)"""
    return sorted(idx for idx, step in b_steps.items() if error_type(step) == "NotImplemented")


def _leaf_wall_index(b_steps):
    """The single leaf device wall: the deepest stepIndex in b whose
    error.type is "NotImplemented" AND whose command is NOT flow-control.

    This is the actual device verb that threw — the OWED verb, and the
    boundary past which the oracle kept going (oracle-only steps beyond it are
    the un-reached tail, notReached). The leaf is always the deepest wall
    index: a leaf hard-stops the flow the instant it throws, so its ancestor
    wrappers (lower stepIndexes, assigned when they started) only ever record
    their propagated NotImplemented afterwards, at indexes below the leaf's.
    None if 3.x never walls on a non-flow-control verb."""
    leaves = [
        idx for idx, step in b_steps.items()
        if error_type(step) == "NotImplemented" and not is_flow_control(step)
    ]
    return max(leaves) if leaves else None


def diff_flow(a_steps, b_steps, tol=DEFAULT_TOL, flow_name=None):
    """Compare two {stepIndex: step} dicts. Returns the per-flow result dict.

    Alignment is by stepIndex. A stepIndex present on only one side is
    normally a step-count divergence at that index (not compared further) —
    UNLESS b (the 3.x candidate) hard-stopped at a NotImplemented wall
    (owed_index) and this index is an oracle-only index beyond that wall: the
    3.x candidate trace is a PREFIX of the 2.x oracle trace by design, so the
    un-reached oracle tail is recorded as `notReached`, not a divergence.

    A step with declined:true on either side is logged as a coverage gap and
    is NOT compared as a divergence (declined implies no chosenElement to
    compare meaningfully, and the backend is explicitly opting out of this
    step).

    Every wall step (present on both sides — 2.x has its usual step, 3.x has
    the NotImplemented error at that index) is skipped here too: it's
    classified downstream by fidelity.py, not counted as a divergence. A
    composite (repeat:/retry:/runFlow:) that walls records NotImplemented on
    BOTH the walling leaf step AND its ancestor composite step, at different
    stepIndexes — ALL such indices are excluded from divergence, not just the
    leaf. The `notReached` boundary, though, is the LEAF wall's index (the
    deepest non-flow-control wall) — the point past which the oracle kept
    going. The propagated wrapper walls sit below it (fidelity.py labels them
    WALL_PROPAGATED, distinct from the single OWED leaf).
    """
    all_indices = sorted(set(a_steps) | set(b_steps))
    divergences = []
    coverage_gaps = []
    not_reached = []

    wall_indices = set(_wall_indices(b_steps))
    propagated_wall_indices = sorted(
        idx for idx in wall_indices if is_flow_control(b_steps[idx])
    )
    leaf_wall_index = _leaf_wall_index(b_steps)
    # The un-reached-tail boundary is the leaf wall. Fall back to the deepest
    # wall of any kind only in the (unexpected) case that a wall exists with no
    # non-flow-control leaf, so the tail is never mis-attributed to divergence.
    boundary = leaf_wall_index if leaf_wall_index is not None else (
        max(wall_indices) if wall_indices else None
    )

    for idx in all_indices:
        a_step = a_steps.get(idx)
        b_step = b_steps.get(idx)

        if a_step is None or b_step is None:
            if boundary is not None and idx > boundary and a_step is not None and b_step is None:
                not_reached.append(_not_reached_entry(idx, a_step))
                continue
            divergences.append({
                "stepIndex": idx,
                "kind": "step-count",
                "a": "present" if a_step is not None else "missing",
                "b": "present" if b_step is not None else "missing",
            })
            continue

        if idx in wall_indices:
            # A device wall (leaf or propagated) — classified by fidelity.py,
            # never a divergence here.
            continue

        a_declined = bool(a_step.get("declined"))
        b_declined = bool(b_step.get("declined"))
        if a_declined:
            coverage_gaps.append(_coverage_gap(idx, a_step, a_step.get("backendId", "a")))
        if b_declined:
            coverage_gaps.append(_coverage_gap(idx, b_step, b_step.get("backendId", "b")))
        if a_declined or b_declined:
            # A declined step is coverage, not divergence — skip comparison.
            continue

        for div in diff_step(a_step, b_step, tol):
            divergences.append({"stepIndex": idx, **div})

    divergences.sort(key=lambda d: d["stepIndex"])
    coverage_gaps.sort(key=lambda g: g["stepIndex"])
    not_reached.sort(key=lambda n: n["stepIndex"])

    first_divergent_step = divergences[0]["stepIndex"] if divergences else None

    result = {
        "stepsCompared": len(all_indices),
        "divergences": divergences,
        "firstDivergentStep": first_divergent_step,
        "coverageGaps": coverage_gaps,
        "notReached": not_reached,
        # owedIndex is the LEAF wall — the single OWED device verb and the
        # notReached boundary. wallIndices is every NotImplemented step
        # (leaf + propagated) excluded from divergence; propagatedWallIndices
        # is the flow-control subset (fidelity.py labels those WALL_PROPAGATED).
        "owedIndex": boundary,
        "leafWallIndex": leaf_wall_index,
        "wallIndices": sorted(wall_indices),
        "propagatedWallIndices": propagated_wall_indices,
    }
    if flow_name is not None:
        result = {"flow": flow_name, **result}
    return result


def diff_flow_files(a_path, b_path, tol=DEFAULT_TOL, flow_name=None):
    a_steps = load_steps(a_path)
    b_steps = load_steps(b_path)
    return diff_flow(a_steps, b_steps, tol=tol, flow_name=flow_name)


def diff_corpus(corpus_dir, tol=DEFAULT_TOL):
    """Aggregate diff_flow_files over every per-flow subdir of corpus_dir.

    Each flow subdir must contain a/steps.jsonl and b/steps.jsonl.
    """
    corpus_path = Path(corpus_dir)
    if not corpus_path.exists() or not corpus_path.is_dir():
        raise TraceError(f"corpus directory not found: {corpus_path}")

    flow_dirs = sorted(p for p in corpus_path.iterdir() if p.is_dir())
    if not flow_dirs:
        raise TraceError(f"corpus directory has no flow subdirs: {corpus_path}")

    flow_results = []
    for flow_dir in flow_dirs:
        a_file = flow_dir / "a" / "steps.jsonl"
        b_file = flow_dir / "b" / "steps.jsonl"
        result = diff_flow_files(a_file, b_file, tol=tol, flow_name=flow_dir.name)
        flow_results.append(result)

    total_flows = len(flow_results)
    flows_zero = sum(1 for r in flow_results if not r["divergences"])
    flows_with = total_flows - flows_zero

    div_counts_by_kind = {}
    gap_counts_by_command = {}
    for r in flow_results:
        for d in r["divergences"]:
            div_counts_by_kind[d["kind"]] = div_counts_by_kind.get(d["kind"], 0) + 1
        for g in r["coverageGaps"]:
            command = g["command"] or "unknown"
            gap_counts_by_command[command] = gap_counts_by_command.get(command, 0) + 1

    summary = {
        "totalFlows": total_flows,
        "flowsZeroDivergence": flows_zero,
        "flowsWithDivergence": flows_with,
        "divergenceCountsByKind": div_counts_by_kind,
        "coverageGapCountsByCommand": gap_counts_by_command,
        "flows": flow_results,
    }
    return summary


def _print_result(result, as_json):
    if as_json:
        print(json.dumps(result, indent=2, sort_keys=False))
        return
    print(json.dumps(result, indent=2, sort_keys=False))


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Diff two per-backend step traces (or a corpus of flows) "
        "for the zero-divergence validation gate."
    )
    parser.add_argument("--a", help="path to backend A's steps.jsonl")
    parser.add_argument("--b", help="path to backend B's steps.jsonl")
    parser.add_argument("--corpus", help="path to a corpus dir of per-flow a/steps.jsonl + b/steps.jsonl subdirs")
    parser.add_argument("--tol", type=int, default=DEFAULT_TOL, help=f"coordinate tolerance in pixels (default {DEFAULT_TOL})")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON (currently the only output format)")
    args = parser.parse_args(argv)

    if args.corpus:
        if args.a or args.b:
            print("error: --corpus cannot be combined with --a/--b", file=sys.stderr)
            return 2
        try:
            summary = diff_corpus(args.corpus, tol=args.tol)
        except TraceError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        _print_result(summary, args.json)
        return 0 if summary["flowsWithDivergence"] == 0 else 1

    if not args.a or not args.b:
        print("error: must supply either --corpus, or both --a and --b", file=sys.stderr)
        return 2

    try:
        result = diff_flow_files(args.a, args.b, tol=args.tol)
    except TraceError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    _print_result(result, args.json)
    return 0 if not result["divergences"] else 1


if __name__ == "__main__":
    sys.exit(main())
