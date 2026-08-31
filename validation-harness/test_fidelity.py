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


def test_composite_wall_only_leaf_is_owed_wrapper_is_propagated(tmp_path):
    # A repeat:/retry:/runFlow: composite that walls records NotImplemented
    # at TWO different stepIndexes in the 3.x trace: the ancestor composite
    # step (index 2, RepeatCommand) AND the walling leaf step underneath it
    # (index 3, the device verb). Neither may be diff-compared against the
    # 2.x oracle's PASS (both are excluded from DIVERGE). But ONLY the leaf is
    # OWED — the flow-control wrapper sits above the device seam and can never
    # be a genuine device wall, so its propagated NotImplemented is
    # WALL_PROPAGATED, counted toward nothing.
    twox = tmp_path / "2x.jsonl"
    threex = tmp_path / "3x.jsonl"
    _write(twox, [
        {"stepIndex": 0, "backendId": "2x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 1, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 2, "backendId": "2x", "command": {"type": "RepeatCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 3, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 4, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 5, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
    ])
    _write(threex, [
        {"stepIndex": 0, "backendId": "3x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 1, "backendId": "3x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        # the ancestor composite step — carries the leaf's wall PROPAGATED up
        {"stepIndex": 2, "backendId": "3x", "command": {"type": "RepeatCommand"}, "verdict": "ERROR", "chosenElement": None,
         "error": {"type": "NotImplemented", "message": "repeat"}},
        # the walling leaf step underneath it — the real device wall
        {"stepIndex": 3, "backendId": "3x", "command": {"type": "TapOnElementCommand"}, "verdict": "ERROR", "chosenElement": None,
         "error": {"type": "NotImplemented", "message": "tapOnElement"}},
    ])

    rep = fidelity.fidelity_report(str(twox), str(threex), tol=2, flow_name="composite-wall")

    assert rep["diverge"] == 0                 # KEY assertion: no spurious divergence
    assert rep["fidelityGreen"] is True
    assert rep["owed"] == 1                    # ONLY the leaf verb (index 3) is OWED
    assert rep["wall_propagated"] == 1         # the RepeatCommand wrapper (index 2)
    assert rep["agree"] == 2                   # steps 0-1 matched
    assert rep["not_reached"] == 2             # steps 4-5 are the un-reached oracle tail

    by_index = {s["stepIndex"]: s for s in rep["steps"]}
    assert by_index[2]["status"] == "WALL_PROPAGATED"   # wrapper, NOT owed
    assert by_index[3]["status"] == "OWED"              # the leaf device verb
    # the WHY is threaded from the trace's error.message onto the step
    assert by_index[3]["errorMessage"] == "tapOnElement"
    assert by_index[0]["status"] == "AGREE"
    assert by_index[1]["status"] == "AGREE"
    assert by_index[4]["status"] == "NOT_REACHED"
    assert by_index[5]["status"] == "NOT_REACHED"


def test_nested_composite_wall_both_wrappers_propagated_one_leaf_owed(tmp_path):
    # A runFlow: containing a repeat: containing the walling verb. The 3.x
    # trace records NotImplemented at THREE indexes: the leaf verb (deepest,
    # index 3) plus BOTH ancestor wrappers (repeat at 2, runFlow at 1). Still
    # exactly ONE OWED (the leaf); both wrappers are WALL_PROPAGATED; nothing
    # DIVERGEs.
    twox = tmp_path / "2x.jsonl"
    threex = tmp_path / "3x.jsonl"
    _write(twox, [
        {"stepIndex": 0, "backendId": "2x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 1, "backendId": "2x", "command": {"type": "RunFlowCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 2, "backendId": "2x", "command": {"type": "RepeatCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 3, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 4, "backendId": "2x", "command": {"type": "TapOnElementCommand"}, "verdict": "PASS", "chosenElement": None},
        {"stepIndex": 5, "backendId": "2x", "command": {"type": "AssertConditionCommand"}, "verdict": "PASS", "chosenElement": None},
    ])
    _write(threex, [
        {"stepIndex": 0, "backendId": "3x", "command": {"type": "LaunchAppCommand"}, "verdict": "PASS", "chosenElement": None},
        # both wrappers carry the propagated wall
        {"stepIndex": 1, "backendId": "3x", "command": {"type": "RunFlowCommand"}, "verdict": "ERROR", "chosenElement": None,
         "error": {"type": "NotImplemented", "message": "runFlow"}},
        {"stepIndex": 2, "backendId": "3x", "command": {"type": "RepeatCommand"}, "verdict": "ERROR", "chosenElement": None,
         "error": {"type": "NotImplemented", "message": "repeat"}},
        # the single leaf device wall
        {"stepIndex": 3, "backendId": "3x", "command": {"type": "TapOnElementCommand"}, "verdict": "ERROR", "chosenElement": None,
         "error": {"type": "NotImplemented", "message": "tapOnElement"}},
    ])

    rep = fidelity.fidelity_report(str(twox), str(threex), tol=2, flow_name="nested-composite")

    assert rep["diverge"] == 0
    assert rep["owed"] == 1                     # exactly one leaf OWED
    assert rep["wall_propagated"] == 2          # both runFlow + repeat wrappers
    assert rep["agree"] == 1                    # step 0
    assert rep["not_reached"] == 2             # steps 4-5 beyond the leaf wall (index 3)

    by_index = {s["stepIndex"]: s for s in rep["steps"]}
    assert by_index[1]["status"] == "WALL_PROPAGATED"   # runFlow wrapper
    assert by_index[2]["status"] == "WALL_PROPAGATED"   # repeat wrapper
    assert by_index[3]["status"] == "OWED"              # leaf verb
    assert by_index[4]["status"] == "NOT_REACHED"
    assert by_index[5]["status"] == "NOT_REACHED"


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
