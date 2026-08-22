#!/usr/bin/env python3
"""viewer.py — emits the human-facing side-by-side fidelity viewer.

fidelity.fidelity_report(...) is the byproduct (diff.json); this module is
the thing a human actually opens. It takes that dict and writes two files
into a run's output directory:

  report.js    window.REPORT = {...};   — the per-flow JS contract
  index.html   copied from viewer/index.html, a self-contained file://
               -openable page that reads window.REPORT via a <script src>
               tag (never fetch — fetch() of a sibling file is blocked when
               the page is opened by double-click from file://).

write_runs_index does the batch-level equivalent: runs.js + window.RUNS_INDEX,
and copies viewer/runs-index.html as the batch out_dir's index.html.

Ruling P6: the two column labels are HARDCODED as "2.x oracle" / "3.x
candidate" here — never echo the trace's own backendId field, which reads
"2x" on the oracle side but "devicecore" on the candidate side and would
mislead a viewer scanning the two columns.
"""
from __future__ import annotations

import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path

_VIEWER_DIR = Path(__file__).parent / "viewer"

_TWOX_LABEL = "2.x oracle"
_THREEX_LABEL = "3.x candidate"


def _video_path(out_dir, side):
    """Relative path to that side's recorded video, if run_differential
    pulled one to `<out_dir>/<side>/screen.mp4`. None otherwise — video is
    optional and the viewer degrades gracefully without it."""
    local = os.path.join(out_dir, side, "screen.mp4")
    return f"./{side}/screen.mp4" if os.path.exists(local) else None


def _side(rep, side_key, label, out_dir):
    steps = rep.get("steps", [])
    if side_key == "2x":
        status = "COMPLETED"  # the oracle always runs to completion by design
        side_steps = [
            {"stepIndex": s["stepIndex"], "label": s["command"], "verdict": s.get("twoxVerdict")}
            for s in steps
        ]
    else:
        diverge = rep.get("diverge", 0)
        owed = rep.get("owed", 0)
        status = "FAIL" if diverge else ("WARNED" if owed else "PASS")
        side_steps = [
            {"stepIndex": s["stepIndex"], "label": s["command"], "verdict": s.get("threexVerdict")}
            for s in steps
            if s["status"] != "NOT_REACHED"
        ]
    return {
        "label": label,
        "status": status,
        "video": _video_path(out_dir, side_key),
        "recordingStartedAtMs": None,
        "steps": side_steps,
    }


def _owed(rep):
    """The first OWED step's command is the device-core "wall" verb — the
    thing 2.x could do that 3.x can't yet."""
    for s in rep.get("steps", []):
        if s["status"] == "OWED":
            return {"verb": s["command"]}
    return None


def _rows(rep):
    return [
        {
            "stepIndex": s["stepIndex"],
            "command": s["command"],
            "status": s["status"],
            "twoxVerdict": s.get("twoxVerdict"),
            "threexVerdict": s.get("threexVerdict"),
            "errorType": s.get("errorType"),
        }
        for s in rep.get("steps", [])
    ]


def write_flow_report(rep, out_dir):
    """Write report.js (window.REPORT) + copy index.html into `out_dir`.

    `rep` is a fidelity.fidelity_report(...) dict (or anything with the same
    flow/steps/diverge/owed shape)."""
    os.makedirs(out_dir, exist_ok=True)
    payload = {
        "kind": "fidelity",
        "flow": rep.get("flow"),
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "twox": _side(rep, "2x", _TWOX_LABEL, out_dir),
        "threex": _side(rep, "3x", _THREEX_LABEL, out_dir),
        "rows": _rows(rep),
        "owed": _owed(rep),
    }
    with open(os.path.join(out_dir, "report.js"), "w") as fh:
        fh.write("window.REPORT = " + json.dumps(payload, indent=2) + ";\n")
    shutil.copy(_VIEWER_DIR / "index.html", os.path.join(out_dir, "index.html"))


def _glyph(flow_row):
    """agree/diverge/walled/error — see task-5.1-brief.md for the ruling.

    walled: OWED at (or before) step 1 — 3.x has essentially nothing built
    for this flow yet. diverge: any real fidelity failure. agree: reached
    past the first step with zero divergence. error: none of the above
    (e.g. the folder failed to run at all — reachDepth 0, nothing to say).
    """
    reach_depth = flow_row.get("reachDepth", 0)
    diverge = flow_row.get("diverge", 0)
    owed = flow_row.get("owed", 0)
    if reach_depth <= 1 and owed > 0:
        return "walled"
    if diverge > 0:
        return "diverge"
    if diverge == 0 and reach_depth > 1:
        return "agree"
    return "error"


def write_runs_index(flow_reports, out_dir):
    """Write runs.js (window.RUNS_INDEX) + copy runs-index.html as
    `out_dir/index.html`. `flow_reports` is a list of dicts each carrying at
    least flow/dir/reachDepth/agree/diverge/owed (a fidelity_report dict with
    a "dir" key added, or run_differential's per-folder report dict)."""
    os.makedirs(out_dir, exist_ok=True)
    flows = []
    for fr in flow_reports:
        flow_name = fr.get("flow") or fr.get("runId")
        flows.append({
            "flow": flow_name,
            "dir": fr.get("dir", flow_name),
            "glyph": _glyph(fr),
            "reachDepth": fr.get("reachDepth", 0),
            "agree": fr.get("agree", 0),
            "diverge": fr.get("diverge", 0),
            "owed": fr.get("owed", 0),
        })
    payload = {"version": 1, "flows": flows}
    with open(os.path.join(out_dir, "runs.js"), "w") as fh:
        fh.write("window.RUNS_INDEX = " + json.dumps(payload, indent=2) + ";\n")
    shutil.copy(_VIEWER_DIR / "runs-index.html", os.path.join(out_dir, "index.html"))
