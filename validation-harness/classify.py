#!/usr/bin/env python3
"""classify.py — the control-subtracted, flakiness-robust gate verdict.

The literal zero-divergence gate assumed each flow renders identically across
two device launches. Real corpus apps don't: loading-state asserts flip and
scrolled elements land at different pixels run-to-run, so two separate runs
diverge even on the SAME backend. To hold the legacy backend accountable for
its own behavior — and nothing else — we subtract the app's run-to-run noise
by running BOTH backends multiple times and letting each backend detect its
own flakiness.

Each flow is run several times on one emulator, app state reset between each —
by default TWO stock and TWO legacy passes, interleaved (stock, legacy, stock,
legacy). A single stock control is not enough: a coin-flip step (an assert on a
sometimes-loaded element) can land both stock samples on the same side by luck
while legacy lands on the other, manufacturing a false divergence. Running each
backend twice catches this — a genuinely flaky step flip-flops WITHIN a
backend, so it is excluded no matter how the other backend's samples fell.

Behavioral aspects = verdict, chosen-element presence, chosen-element identity
(text + resourceId). Per flow:

  * A step is "reproducible" for a backend iff all that backend's runs agree on
    it (same existence, and when present the same behavioral value).
  * k = the first step that is NOT reproducible in EITHER backend (a flaky step
    forks control flow, so nothing downstream aligns). Steps before k are
    judged; steps at/after k are EXCLUDED and reported (the flakiness we
    "aggressively drop" — logged, never silent). k = None ⇒ both backends fully
    reproducible.
  * On each judged step, the stock consensus and the legacy consensus MUST
    match — same existence, same verdict/presence/identity. The first judged
    step where they differ is the flow's REAL divergence.

A flow is GREEN iff it has no real divergence; the corpus is GREEN iff every
flow is. A real backend divergence is deterministic, so any RED flow is re-run
by the harness — a divergence that does not reproduce was flakiness the sample
size missed, not a backend bug.

Coordinates never gate behavior (same element, different pixel = app layout /
scroll physics, not backend). Reported only: on a judged step where stock's own
center is rock-stable across its runs (≤ tol), legacy must be within tol too.

Pure, local, stdlib-only. Reuses diff_traces.load_steps for input parsing.
"""
import argparse
import json
import sys
from pathlib import Path

from diff_traces import load_steps, TraceError

DEFAULT_TOL = 2
IDENTITY_FIELDS = ("text", "resourceId")


def _verdict(step):
    return step.get("verdict") if step else None


def _present(step):
    return step is not None and step.get("chosenElement") is not None


def _identity(step):
    elem = step.get("chosenElement") if step else None
    if not elem:
        return None
    return tuple(elem.get(f) for f in IDENTITY_FIELDS)


def _center(step):
    elem = step.get("chosenElement") if step else None
    if not elem:
        return None
    return (elem.get("centerX"), elem.get("centerY"))


def behavioral_equal(a, b):
    """Same verdict, same presence, and — when both present — same identity."""
    if _verdict(a) != _verdict(b):
        return False
    if _present(a) != _present(b):
        return False
    if _present(a) and _present(b) and _identity(a) != _identity(b):
        return False
    return True


def _run_state(runs, idx):
    """Reproducibility of step `idx` across one backend's runs.

    Returns ('flaky',) | ('absent',) | ('present', step). 'absent' means every
    run agrees the step did not occur; 'present' carries the agreed step object.
    """
    cells = [r.get(idx) for r in runs]
    exists = [c is not None for c in cells]
    if len(set(exists)) > 1:
        return ("flaky",)          # some runs ran the step, others didn't
    if not exists[0]:
        return ("absent",)         # consistently did not occur
    first = cells[0]
    for c in cells[1:]:
        if not behavioral_equal(first, c):
            return ("flaky",)      # ran every time but behaved differently
    return ("present", first)


def classify_flow(stocks, legacies, tol=DEFAULT_TOL, flow_name=None):
    """stocks/legacies are lists of {stepIndex: step} dicts (≥1 each)."""
    all_idx = sorted(set().union(*stocks, *legacies))

    # k = first step flaky in EITHER backend
    k = None
    for idx in all_idx:
        if _run_state(stocks, idx)[0] == "flaky" or _run_state(legacies, idx)[0] == "flaky":
            k = idx
            break
    judged = [i for i in all_idx if (k is None or i < k)]

    real_step = None
    real_detail = None
    coord_flags = []
    for idx in judged:
        ss = _run_state(stocks, idx)
        ls = _run_state(legacies, idx)
        # neither is 'flaky' here (idx < k)
        if ss[0] != ls[0]:
            real_step = idx
            real_detail = {"kind": "presence-or-step-count",
                           "stock": ss[0], "legacy": ls[0]}
            break
        if ss[0] == "present":
            s_step, l_step = ss[1], ls[1]
            if not behavioral_equal(s_step, l_step):
                real_step = idx
                real_detail = {
                    "kind": "behavioral",
                    "verdict": {"legacy": _verdict(l_step), "stock": _verdict(s_step)},
                    "present": {"legacy": _present(l_step), "stock": _present(s_step)},
                    "identity": {"legacy": _identity(l_step), "stock": _identity(s_step)},
                }
                break
            # coordinate flag (report-only): the ONLY case that could indicate a
            # real backend coordinate difference — BOTH backends place the (same)
            # element reproducibly, yet at different centers. If either backend is
            # itself positionally noisy (scroll/layout jitter), it is app noise,
            # not the backend, and we do not flag.
            centers_s = [_center(r.get(idx)) for r in stocks]
            centers_l = [_center(r.get(idx)) for r in legacies]
            if all(c and None not in c for c in centers_s + centers_l):
                sxs = [c[0] for c in centers_s]; sys_ = [c[1] for c in centers_s]
                lxs = [c[0] for c in centers_l]; lys = [c[1] for c in centers_l]
                stock_stable = (max(sxs) - min(sxs)) <= tol and (max(sys_) - min(sys_)) <= tol
                legacy_stable = (max(lxs) - min(lxs)) <= tol and (max(lys) - min(lys)) <= tol
                differ = abs(sxs[0] - lxs[0]) > tol or abs(sys_[0] - lys[0]) > tol
                if stock_stable and legacy_stable and differ:
                    coord_flags.append({
                        "stepIndex": idx,
                        "legacyCenter": [lxs[0], lys[0]],
                        "stockCenter": [sxs[0], sys_[0]],
                    })

    total = len(set().union(*stocks))
    result = {
        "green": real_step is None,
        "kFlaky": k,
        "bothReproducible": k is None,
        "totalStockSteps": total,
        "judgedSteps": len(judged),
        "excludedSteps": len(all_idx) - len(judged),
        "realDivergenceStep": real_step,
        "realDivergenceDetail": real_detail,
        "coordFlags": coord_flags,
    }
    if flow_name is not None:
        result = {"flow": flow_name, **result}
    return result


def classify_flow_files(stock_paths, legacy_paths, tol=DEFAULT_TOL, flow_name=None):
    stocks = [load_steps(p) for p in stock_paths]
    legacies = [load_steps(p) for p in legacy_paths]
    return classify_flow(stocks, legacies, tol=tol, flow_name=flow_name)


def _discover(flow_dir):
    """Return (stock_paths, legacy_paths) from a flow subdir.

    Stock runs live in subdirs starting 's' (s1, s2, …), legacy in 'l' (l, l1,
    l2, …). Only subdirs with a steps.jsonl count.
    """
    stocks, legacies = [], []
    for sub in sorted(p for p in flow_dir.iterdir() if p.is_dir()):
        f = sub / "steps.jsonl"
        if not f.exists():
            continue
        if sub.name.startswith("s"):
            stocks.append(f)
        elif sub.name.startswith("l"):
            legacies.append(f)
    return stocks, legacies


def classify_corpus(corpus_dir, tol=DEFAULT_TOL):
    root = Path(corpus_dir)
    if not root.is_dir():
        raise TraceError(f"corpus directory not found: {root}")
    flow_dirs = sorted(p for p in root.iterdir() if p.is_dir())
    if not flow_dirs:
        raise TraceError(f"corpus directory has no flow subdirs: {root}")

    flows, incomplete = [], []
    for fd in flow_dirs:
        stocks, legacies = _discover(fd)
        if not stocks or not legacies:
            incomplete.append(fd.name)
            continue
        try:
            flows.append(classify_flow_files(stocks, legacies, tol=tol, flow_name=fd.name))
        except TraceError as e:
            incomplete.append(f"{fd.name} ({e})")

    reds = [f for f in flows if not f["green"]]
    summary = {
        "corpusGreen": not reds and not incomplete,
        "totalFlows": len(flow_dirs),
        "classified": len(flows),
        "incomplete": incomplete,
        "flowsGreen": sum(1 for f in flows if f["green"]),
        "flowsRed": len(reds),
        "flowsBothReproducible": sum(1 for f in flows if f["bothReproducible"]),
        "totalJudgedSteps": sum(f["judgedSteps"] for f in flows),
        "totalExcludedSteps": sum(f["excludedSteps"] for f in flows),
        "totalCoordFlags": sum(len(f["coordFlags"]) for f in flows),
        "redFlows": [{"flow": f["flow"], "step": f["realDivergenceStep"],
                      "detail": f["realDivergenceDetail"]} for f in reds],
        "flows": flows,
    }
    return summary


def main(argv=None):
    ap = argparse.ArgumentParser(description="Control-subtracted, flakiness-robust gate verdict.")
    ap.add_argument("--stock", action="append", default=[], help="a stock steps.jsonl (repeatable)")
    ap.add_argument("--legacy", action="append", default=[], help="a legacy steps.jsonl (repeatable)")
    ap.add_argument("--corpus", help="dir of per-flow subdirs (s*/ stock, l*/ legacy)")
    ap.add_argument("--tol", type=int, default=DEFAULT_TOL)
    args = ap.parse_args(argv)

    if args.corpus:
        if args.stock or args.legacy:
            print("error: --corpus cannot be combined with --stock/--legacy", file=sys.stderr)
            return 2
        try:
            summary = classify_corpus(args.corpus, tol=args.tol)
        except TraceError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        print(json.dumps(summary, indent=2))
        return 0 if summary["corpusGreen"] else 1

    if not (args.stock and args.legacy):
        print("error: supply --corpus, or ≥1 --stock and ≥1 --legacy", file=sys.stderr)
        return 2
    try:
        result = classify_flow_files(args.stock, args.legacy, tol=args.tol)
    except TraceError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2))
    return 0 if result["green"] else 1


if __name__ == "__main__":
    sys.exit(main())
