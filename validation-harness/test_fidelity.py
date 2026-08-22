import json
from pathlib import Path
import fidelity

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
    # NOTE: the OWED-by-error-type classification lands in Task 4.1. Today,
    # diff_traces treats a served-but-ERROR step as a DIVERGE (not a coverage
    # gap), so these two assertions are expected to fail until 4.1 teaches
    # fidelity_report to recognize a NotImplemented error as OWED rather than
    # a divergence. Left in place (not weakened) so 4.1's TDD loop starts RED
    # on exactly this test, per the task-3.2 brief.
    assert rep["owed"] == 1                # step 1 is the OWED wall (added in Task 4.1)
    assert rep["diverge"] == 0             # the prefix boundary is NOT a divergence
