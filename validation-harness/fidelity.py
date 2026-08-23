#!/usr/bin/env python3
"""fidelity.py — the fidelity report: reframes diff_traces.diff_flow as an
agree/diverge/owed/not-reached accounting between the 2.x oracle and the 3.x
candidate.

The question the device-core validation program exists to answer: *where does
3.x agree with the 2.x oracle, and where does it not?* The 3.x candidate
trace is a PREFIX of the 2.x oracle trace by design — it hard-stops at the
first device verb device-core hasn't built (a step with error.type ==
"NotImplemented"). Given two per-step traces for the SAME flow (one per
binary), classify every 2.x oracle step:

  - AGREE       : 3.x ran the verb and produced 2.x's verdict + a compatible
                  chosen element (verdict exact, element identity compatible,
                  coords within tol where comparable). This is the fidelity
                  signal.
  - DIVERGE     : 3.x ran the verb but disagreed with the oracle. A real
                  fidelity failure.
  - OWED        : the device-core wall — the single LEAF device verb that
                  errored with error.type=="NotImplemented" (the verb isn't
                  built yet). Not a divergence — it's what 3.x can't test yet.
  - WALL_PROPAGATED : a flow-control/composite wrapper (repeat:/retry:/
                  runFlow:) whose NotImplemented is not its own — it is the
                  leaf's wall PROPAGATING up through the wrapper as the
                  exception unwinds. A wrapper sits above the device seam and
                  can never be a genuine device wall, so it is neither OWED
                  nor DIVERGE nor AGREE, and counts toward none of the
                  agree/diverge/owed/not_reached tallies.
  - NOT_REACHED : an oracle-only step beyond the leaf wall (or, absent a
                  wall, any step 3.x never produced) — the un-reached oracle
                  tail. Not a divergence either.

`reachDepth` = how many steps 3.x produced. As 3.x gains verbs, OWED steps
become AGREE/DIVERGE and NOT_REACHED steps become reachable — the framework
doesn't change, only the numbers do.

The comparison core is diff_traces.diff_flow (a=2.x oracle, b=3.x candidate):
declined steps are coverage gaps, the OWED wall and the un-reached prefix
tail are carried in owedIndex/notReached, everything else reached on both
sides is verdict+identity+coord checked. No run_gate/remote dependency —
this module is pure, local, and stdlib+diff_traces only.
"""
import diff_traces


def fidelity_report(twox_path, threex_path, tol, flow_name):
    """Reframe diff_flow (a=2.x oracle, b=3.x candidate) as a fidelity report.

    load_steps returns {stepIndex: step} dicts, aligned by stepIndex. 2.x is
    the oracle spine; each of its steps is classified by what 3.x did at the
    same index.
    """
    a = diff_traces.load_steps(str(twox_path))     # {idx: step} — 2.x oracle
    b = diff_traces.load_steps(str(threex_path))    # {idx: step} — 3.x candidate
    diff = diff_traces.diff_flow(a, b, tol=tol, flow_name=flow_name)

    diverged_idx = {d.get("stepIndex") for d in diff["divergences"]}
    # owedIndex is the LEAF wall (the single OWED device verb) and the
    # notReached boundary.
    owed_index = diff.get("owedIndex")

    steps = []
    for idx in sorted(a):
        sa = a[idx]
        sb = b.get(idx)
        cmd = (sa.get("command") or {}).get("type", "?") if isinstance(sa.get("command"), dict) else "?"
        step_error_type = diff_traces.error_type(sb) if sb is not None else None
        # The device-core wall carries a human-readable reason in error.message
        # (e.g. "launchApp modifier clearState") — the WHY behind the OWED step,
        # which the viewer surfaces so a reader sees the specific unbuilt
        # capability, not just the command type.
        step_error_message = (sb.get("error") or {}).get("message") if sb is not None else None

        if step_error_type == "NotImplemented":
            # A NotImplemented on a flow-control wrapper (repeat:/retry:/
            # runFlow:) is the leaf's wall propagating up, never a genuine
            # device wall — WALL_PROPAGATED, counted toward nothing. Only the
            # actual leaf device verb that threw is OWED.
            status = "WALL_PROPAGATED" if diff_traces.is_flow_control(sb) else "OWED"
        elif owed_index is not None and idx > owed_index:
            status = "NOT_REACHED"      # oracle-only tail beyond the leaf device-core wall
        elif idx in diverged_idx:
            # Reached-on-both disagreement, OR (when owed_index is None, i.e.
            # 3.x never hit a NotImplemented wall) a one-sided step that
            # diff_traces recorded as a step-count divergence — sb is None in
            # that case, and diff_traces is the one place that decides
            # divergence-vs-not-reached for a one-sided index.
            status = "DIVERGE"
        else:
            status = "AGREE"            # reached on both sides and matched

        steps.append({
            "stepIndex": idx,
            "command": cmd,
            "status": status,
            "twoxVerdict": sa.get("verdict"),
            "threexVerdict": (sb or {}).get("verdict"),
            "errorType": step_error_type,
            "errorMessage": step_error_message,
        })

    agree = sum(1 for s in steps if s["status"] == "AGREE")
    diverge = sum(1 for s in steps if s["status"] == "DIVERGE")
    owed = sum(1 for s in steps if s["status"] == "OWED")
    not_reached = sum(1 for s in steps if s["status"] == "NOT_REACHED")
    wall_propagated = sum(1 for s in steps if s["status"] == "WALL_PROPAGATED")
    reach_depth = len(b)  # how many steps 3.x produced before stopping

    return {
        "flow": flow_name,
        "reachDepth": reach_depth,
        "totalLegacySteps": len(a),
        "agree": agree,
        "diverge": diverge,
        "owed": owed,
        "not_reached": not_reached,
        "wall_propagated": wall_propagated,
        # --- aliases kept for existing callers (run_differential.py) ---
        "served": agree + diverge,      # reached on both sides, agreeing or not
        "missing": not_reached,         # steps 3.x never produced
        "fidelityGreen": diverge == 0,
        "steps": steps,
        "rawDiff": diff,
    }
