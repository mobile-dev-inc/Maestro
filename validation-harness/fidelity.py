#!/usr/bin/env python3
"""fidelity.py — the fidelity report: reframes diff_traces.diff_flow as an
agree/diverge/owed accounting between the 2.x oracle and the 3.x candidate.

The question the device-core validation program exists to answer: *where does
3.x agree with the 2.x oracle, and where does it not?* Given two per-step
traces for the SAME flow (one per binary), classify every step:

  - SERVED + AGREE     : 3.x ran the verb and produced 2.x's verdict + chosen
                         element (verdict exact, element identity exact,
                         coords within tol). This is the fidelity signal.
  - SERVED + DIVERGE   : 3.x ran the verb but disagreed with the oracle.
                         A real fidelity failure.
  - OWED (coverage gap): 3.x declined the verb (not implemented yet). Not a
                         divergence — it's what 3.x can't test yet.

`reachDepth` = how many steps 3.x got through before the flow ended or a
served step FAILed/ERRORed. As 3.x gains verbs, OWED steps become SERVED and
this report answers the agreement question at ever-greater depth — the
framework doesn't change, only the numbers do.

The comparison core is diff_traces.diff_flow (a=2.x oracle, b=3.x candidate):
declined steps are coverage gaps, everything else is verdict+identity+coord
checked. No run_gate/remote dependency — this module is pure, local, and
stdlib+diff_traces only.
"""
import diff_traces  # noqa: E402


def fidelity_report(twox_path, threex_path, tol, flow_name):
    """Reframe diff_flow (a=2.x oracle, b=3.x candidate) as a fidelity report.

    load_steps returns {stepIndex: step} dicts, aligned by stepIndex. 2.x is
    the oracle spine; each of its steps is classified by what 3.x did at the
    same index.
    """
    a = diff_traces.load_steps(str(twox_path))     # {idx: step}
    b = diff_traces.load_steps(str(threex_path))    # {idx: step}
    diff = diff_traces.diff_flow(a, b, tol=tol, flow_name=flow_name)

    diverged_idx = {d.get("stepIndex") for d in diff["divergences"]}
    gap_idx = {g.get("stepIndex") for g in diff["coverageGaps"]}

    steps = []
    for idx in sorted(a):
        sa = a[idx]
        sb = b.get(idx)
        cmd = (sa.get("command") or {}).get("type", "?") if isinstance(sa.get("command"), dict) else "?"
        if sb is None:
            status = "MISSING"        # 3.x run ended before this step (aborted upstream)
        elif idx in gap_idx or sb.get("declined"):
            status = "OWED"           # coverage gap — 3.x declined (verb not implemented)
        elif idx in diverged_idx:
            status = "DIVERGE"        # served but disagreed with the oracle
        else:
            status = "AGREE"          # served + matched the oracle (verdict + element identity + coords)
        steps.append({
            "stepIndex": idx, "command": cmd, "status": status,
            "legacyVerdict": sa.get("verdict"),
            "deviceCoreVerdict": (sb or {}).get("verdict"),
        })

    served = [s for s in steps if s["status"] in ("AGREE", "DIVERGE")]
    owed = sum(1 for s in steps if s["status"] == "OWED")
    reach_depth = len(b)  # how far 3.x got before stopping
    return {
        "flow": flow_name,
        "reachDepth": reach_depth,
        "deviceCoreSteps": reach_depth,   # alias kept for callers/report readers
        "totalLegacySteps": len(a),
        "served": len(served),
        "agree": sum(1 for s in steps if s["status"] == "AGREE"),
        "diverge": sum(1 for s in steps if s["status"] == "DIVERGE"),
        "owed": owed,
        "owedCoverageGaps": owed,          # alias kept for callers/report readers
        "missing": sum(1 for s in steps if s["status"] == "MISSING"),
        "fidelityGreen": diff["divergences"] == [],   # served steps all agree
        "steps": steps,
        "rawDiff": diff,
    }
