import json, re
from pathlib import Path
import viewer

def _report():
    return {
        "flow": "settings-fidelity", "reachDepth": 2, "agree": 1, "diverge": 0, "owed": 1,
        "steps": [
            {"stepIndex":0,"command":"LaunchAppCommand","status":"AGREE","twoxVerdict":"PASS","threexVerdict":"PASS","errorType":None},
            {"stepIndex":1,"command":"SetLocationCommand","status":"OWED","twoxVerdict":"PASS","threexVerdict":"ERROR","errorType":"NotImplemented"},
            {"stepIndex":2,"command":"TapOnElementCommand","status":"NOT_REACHED","twoxVerdict":"PASS","threexVerdict":None,"errorType":None},
        ],
    }

def test_write_flow_report_emits_window_REPORT(tmp_path):
    d = tmp_path / "run1"; d.mkdir()
    viewer.write_flow_report(_report(), str(d))
    js = (d / "report.js").read_text()
    assert js.startswith("window.REPORT =")
    payload = json.loads(re.sub(r"^window\.REPORT = (.*);\s*$", r"\1", js, flags=re.S))
    assert payload["kind"] == "fidelity"
    assert [r["status"] for r in payload["rows"]] == ["AGREE","OWED","NOT_REACHED"]
    assert payload["owed"]["verb"] == "SetLocationCommand"
    assert (d / "index.html").exists()

def _composite_report():
    # A repeat: wrapper (index 2) carries the leaf's wall PROPAGATED; only the
    # leaf (index 3) is OWED. The wrapper row must be WALL_PROPAGATED and
    # owed.verb must name the LEAF device verb, not the RepeatCommand.
    return {
        "flow": "settings-composite", "reachDepth": 4, "agree": 2, "diverge": 0,
        "owed": 1, "wall_propagated": 1,
        "steps": [
            {"stepIndex":0,"command":"LaunchAppCommand","status":"AGREE","twoxVerdict":"PASS","threexVerdict":"PASS","errorType":None},
            {"stepIndex":1,"command":"AssertConditionCommand","status":"AGREE","twoxVerdict":"PASS","threexVerdict":"PASS","errorType":None},
            {"stepIndex":2,"command":"RepeatCommand","status":"WALL_PROPAGATED","twoxVerdict":"PASS","threexVerdict":"ERROR","errorType":"NotImplemented","errorMessage":"repeat"},
            {"stepIndex":3,"command":"LaunchAppCommand","status":"OWED","twoxVerdict":"PASS","threexVerdict":"ERROR","errorType":"NotImplemented","errorMessage":"launchApp modifier clearState"},
            {"stepIndex":4,"command":"AssertConditionCommand","status":"NOT_REACHED","twoxVerdict":"PASS","threexVerdict":None,"errorType":None,"errorMessage":None},
        ],
    }

def test_flow_report_owed_verb_is_leaf_not_wrapper(tmp_path):
    d = tmp_path / "composite"; d.mkdir()
    viewer.write_flow_report(_composite_report(), str(d))
    js = (d / "report.js").read_text()
    payload = json.loads(re.sub(r"^window\.REPORT = (.*);\s*$", r"\1", js, flags=re.S))
    statuses = [r["status"] for r in payload["rows"]]
    assert statuses == ["AGREE","AGREE","WALL_PROPAGATED","OWED","NOT_REACHED"]
    # owed.verb is the LEAF device verb (the OWED step), never the RepeatCommand wrapper.
    assert payload["owed"]["verb"] == "LaunchAppCommand"
    # owed.reason surfaces the WHY (error.message) — the specific unbuilt modifier.
    assert payload["owed"]["reason"] == "launchApp modifier clearState"
    # the message is also on the OWED row itself, for the in-table amber cell.
    owed_row = next(r for r in payload["rows"] if r["status"] == "OWED")
    assert owed_row["errorMessage"] == "launchApp modifier clearState"
    # the wrapper is a real 3.x step (ERROR), so it stays in the 3.x column;
    # only NOT_REACHED steps are dropped from a side's step list.
    threex_indices = [s["stepIndex"] for s in payload["threex"]["steps"]]
    assert 2 in threex_indices and 4 not in threex_indices

def test_write_runs_index_emits_window_RUNS_INDEX(tmp_path):
    viewer.write_runs_index([{**_report(), "dir": "run1"}], str(tmp_path))
    js = (tmp_path / "runs.js").read_text()
    idx = json.loads(re.sub(r"^window\.RUNS_INDEX = (.*);\s*$", r"\1", js, flags=re.S))
    assert idx["flows"][0]["glyph"] in ("agree","diverge","walled","error")
    assert (tmp_path / "index.html").exists()
