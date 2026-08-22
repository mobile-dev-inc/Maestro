import json
from pathlib import Path
import fidelity

LIVE_DIR = Path(__file__).parent.parent / ".superpowers" / "sdd" / "2026-08-21-devicecore-fidelity-harness-plan"
LIVE_2X = LIVE_DIR / "live-2x-steps.jsonl"
LIVE_3X = LIVE_DIR / "live-3x-steps.jsonl"

def _write(p, rows):
    p.write_text("".join(json.dumps(r) + "\n" for r in rows))

def test_prefix_owed_boundary_is_not_a_divergence(tmp_path):
    twox = tmp_path / "2x.jsonl"; threex = tmp_path / "3x.jsonl"
    _write(twox, [
        {"stepIndex": 0, "backendId": "2x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 1, "backendId": "2x", "command": {"type": "SetLocationCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 2, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
    ])
    _write(threex, [
        {"stepIndex": 0, "backendId": "3x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 1, "backendId": "3x", "command": {"type": "SetLocationCommand"}, "verdict": "ERROR", "chosenElement": None, "error": {"type": "NotImplemented", "message": "setLocation"}},
    ])
    rep = fidelity.fidelity_report(str(twox), str(threex), tol=2, flow_name="f")
    assert rep["agree"] == 1               # step 0 agrees
    assert rep["reachDepth"] == 2          # 3.x got through 2 steps
    assert rep["owed"] == 1                # step 1 is the OWED wall (Task 4.1)
    assert rep["diverge"] == 0             # the prefix boundary is NOT a divergence
    assert rep["not_reached"] == 1         # step 2 is the un-reached oracle tail


def test_reached_step_disagreeing_is_a_divergence(tmp_path):
    twox = tmp_path/"2x.jsonl"; threex = tmp_path/"3x.jsonl"
    _write(twox, [{"stepIndex":0,"backendId":"2x","command":{"type":"AssertConditionCommand"},"verdict":"PASS","chosenElement":None}])
    _write(threex,[{"stepIndex":0,"backendId":"3x","command":{"type":"AssertConditionCommand"},"verdict":"FAIL","chosenElement":None}])
    rep = fidelity.fidelity_report(str(twox), str(threex), tol=2, flow_name="f")
    assert rep["diverge"] == 1 and rep["agree"] == 0 and rep["owed"] == 0


def test_zeroed_bounds_agree_on_center_and_identity(tmp_path):
    twox = tmp_path/"2x.jsonl"; threex = tmp_path/"3x.jsonl"
    common = {"text":"OK","resourceId":None,"centerX":100,"centerY":200}
    _write(twox, [{"stepIndex":0,"backendId":"2x","command":{"type":"TapOnElementCommand"},"verdict":"PASS",
                   "chosenElement":{**common,"x":90,"y":190,"width":20,"height":20,"index":None}}])
    _write(threex,[{"stepIndex":0,"backendId":"3x","command":{"type":"TapOnElementCommand"},"verdict":"PASS",
                   "chosenElement":{**common,"x":0,"y":0,"width":0,"height":0,"index":None}}])
    rep = fidelity.fidelity_report(str(twox), str(threex), tol=2, flow_name="f")
    assert rep["agree"] == 1 and rep["diverge"] == 0   # bounds differ but center+identity agree


def test_live_settings_flow_fully_agrees_no_owed_no_diverge():
    # Real 2.x/3.x captures from the same flow on an emulator: 5 steps,
    # aligned purely by stepIndex (backendId differs by side — "devicecore"
    # vs "2x" — and is NOT used for alignment). Step 3's chosenElement is
    # zeroed on the 3.x side (device-core's zeroed-bounds convention); step 4
    # is a mutual FAIL (AssertionFailure) — the sides agree the assertion
    # failed, which is AGREE, not DIVERGE. No NotImplemented anywhere, so
    # 3.x never walls and the whole oracle trace is reached.
    rep = fidelity.fidelity_report(str(LIVE_2X), str(LIVE_3X), tol=2, flow_name="settings-fidelity")
    assert rep["agree"] == 5
    assert rep["owed"] == 0
    assert rep["diverge"] == 0
    assert rep["not_reached"] == 0
    assert rep["reachDepth"] == 5
