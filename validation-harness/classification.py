"""classification.py — deterministic bucketing of a run's first real divergence.

Reads a fidelity_report dict (out/<runId>/diff.json). The classifier is CORE,
not batch: run_differential (single) and batch_differential (batch) both emit
classification.json via write_classification (this module), so the two are
identical by construction.

First-real-divergence is the first step whose status == "DIVERGE". DIVERGE
already excludes OWED/WALL_PROPAGATED (device walls) and NOT_REACHED (the
un-reached oracle tail) by construction in fidelity.py — those are recorded,
never triaged.

Bucket taxonomy (v1 — the env/strategy marker lists are the tunable part, to be
validated against the full 36-run Android set once emitted; see the spec's open
questions):
  - no real DIVERGE + a wall present -> "capability-gap"
  - no real DIVERGE + no wall        -> "none"  (all-agree)
  - real DIVERGE, env-mismatch marker -> "env-mismatch"   (e.g. clearCache API 34-on-33)
  - real DIVERGE, strategy-gap marker -> "strategy-gap"   (e.g. setPermission 'unset'/'deny')
  - real DIVERGE otherwise            -> "genuine-fidelity"
"""
from __future__ import annotations

_WALL_STATUSES = ("OWED", "WALL_PROPAGATED")

# v1 marker lists — case-insensitive substring match on the first-divergence
# errorType/errorMessage. Tuned in the corpus re-run (Phase A item 5).
# FALSE-NEGATIVE RISK: a genuine fidelity divergence whose message happens to
# mention an API level (or "clearcache"/"setpermission") is misrouted to
# env-mismatch/strategy-gap and never triaged. Revisit the markers against real
# corpus messages in A7 before trusting these buckets to gate triage.
_ENV_MISMATCH_MARKERS = ("api 34", "api 33", "api-34", "api-33", "sdk mismatch",
                         "clearcache")
_STRATEGY_GAP_MARKERS = ("setpermission", "permission strategy", "'unset'",
                         "'deny'", "strategy 'unset'", "strategy 'deny'",
                         "unabletolaunchapp")


def first_real_divergence(diff: dict):
    for s in diff.get("steps", []):
        if s.get("status") == "DIVERGE":
            return s
    return None


def _has_wall(diff: dict) -> bool:
    return any(s.get("status") in _WALL_STATUSES for s in diff.get("steps", []))


def _matches(text: str, markers) -> bool:
    low = (text or "").lower()
    return any(m in low for m in markers)


def bucket_for(diff: dict) -> str:
    frd = first_real_divergence(diff)
    if frd is None:
        return "capability-gap" if _has_wall(diff) else "none"
    blob = f"{frd.get('errorType') or ''} {frd.get('errorMessage') or ''}"
    if _matches(blob, _ENV_MISMATCH_MARKERS):
        return "env-mismatch"
    if _matches(blob, _STRATEGY_GAP_MARKERS):
        return "strategy-gap"
    return "genuine-fidelity"


def signature_for(package: str, diff: dict) -> list:
    frd = first_real_divergence(diff)
    msg = frd.get("errorMessage") if frd else None
    return [package, msg if msg is not None else "<no-divergence>"]


def classify_run(diff: dict, run_id: str, package: str) -> dict:
    frd = first_real_divergence(diff)
    fd = None
    if frd is not None:
        fd = {
            "stepIndex": frd.get("stepIndex"),
            "command": frd.get("command"),
            "status": frd.get("status"),
            "errorType": frd.get("errorType"),
            "errorMessage": frd.get("errorMessage"),
        }
    return {
        "runId": run_id,
        "package": package,
        "bucket": bucket_for(diff),
        "firstDivergence": fd,
        "signature": signature_for(package, diff),
    }


def classify_corpus(entries: list) -> dict:
    runs = [classify_run(e["diff"], e["runId"], e["package"]) for e in entries]
    groups = []
    index = {}
    for r in runs:
        # Key on (bucket, signature), not signature alone: a wall run
        # (capability-gap) and an all-agree run (none) share the surface
        # signature [package, "<no-divergence>"], so a signature-only key would
        # merge them into ONE group whose bucket is whichever inserted first
        # (folder-order dependent). Keeping bucket in the key means groups never
        # span buckets, while same-bucket (package, message) dedupe is unchanged.
        key = (r["bucket"], tuple(r["signature"]))
        if key not in index:
            index[key] = {
                "signature": r["signature"],
                "package": r["package"],
                "message": r["signature"][1],
                "bucket": r["bucket"],
                "runIds": [],
            }
            groups.append(index[key])
        index[key]["runIds"].append(r["runId"])
    return {"runs": runs, "groups": groups}


import json as _json
import os as _os


def write_classification(out_tree_dir: str, aggregate: dict, dest_path: str) -> dict:
    entries = []
    for f in aggregate.get("folders", []):
        if f.get("status") != "ok":
            continue
        run_id = f.get("runId")
        diff_path = _os.path.join(out_tree_dir, run_id, "diff.json")
        if not _os.path.isfile(diff_path):
            continue
        with open(diff_path) as fh:
            diff = _json.load(fh)
        entries.append({"runId": run_id, "package": f.get("package"), "diff": diff})
    result = classify_corpus(entries)
    _os.makedirs(_os.path.dirname(dest_path), exist_ok=True)
    with open(dest_path, "w") as fh:
        _json.dump(result, fh, indent=2)
    return result
