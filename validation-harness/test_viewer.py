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

def test_write_runs_index_emits_window_RUNS_INDEX(tmp_path):
    viewer.write_runs_index([{**_report(), "dir": "run1"}], str(tmp_path))
    js = (tmp_path / "runs.js").read_text()
    idx = json.loads(re.sub(r"^window\.RUNS_INDEX = (.*);\s*$", r"\1", js, flags=re.S))
    assert idx["flows"][0]["glyph"] in ("agree","diverge","walled","error")
    assert (tmp_path / "index.html").exists()
