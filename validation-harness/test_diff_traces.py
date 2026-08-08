"""Pytest cases for diff_traces.py — these ARE the contract (Task 3.2a).

Each test builds two steps.jsonl files (or a corpus dir), runs diff_traces
as a subprocess in CLI mode, and asserts on exit code + JSON output. Driving
it as a subprocess (not importing internals) locks in the CLI/exit-code
contract the gate scripts actually depend on.
"""
import json
import subprocess
import sys
from pathlib import Path

TOOL = Path(__file__).parent / "diff_traces.py"


def run_diff(a_path, b_path, tol=None, extra_args=None):
    args = [sys.executable, str(TOOL), "--a", str(a_path), "--b", str(b_path), "--json"]
    if tol is not None:
        args += ["--tol", str(tol)]
    if extra_args:
        args += extra_args
    proc = subprocess.run(args, capture_output=True, text=True)
    return proc


def run_corpus(corpus_dir, extra_args=None):
    args = [sys.executable, str(TOOL), "--corpus", str(corpus_dir), "--json"]
    if extra_args:
        args += extra_args
    proc = subprocess.run(args, capture_output=True, text=True)
    return proc


def write_jsonl(path, steps):
    path.write_text("\n".join(json.dumps(s) for s in steps) + "\n")


def base_step(step_index=0, backend_id="stock", verdict="PASS", element=None, **extra):
    step = {
        "stepIndex": step_index,
        "backendId": backend_id,
        "command": {"type": "TapOnElementCommand", "selectorText": "Login"},
        "verdict": verdict,
    }
    if element is not None:
        step["chosenElement"] = element
    step.update(extra)
    return step


def elem(x=10, y=20, width=100, height=40, center_x=60, center_y=40, text="Login", resource_id="com.example:id/login"):
    return {
        "x": x, "y": y, "width": width, "height": height,
        "centerX": center_x, "centerY": center_y,
        "text": text, "resourceId": resource_id,
    }


# ---------------------------------------------------------------------------
# 1. Identical traces -> 0 divergences, exit 0
# ---------------------------------------------------------------------------
def test_identical_traces_zero_divergence_exit_zero(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    steps = [
        base_step(0, "legacy", "PASS", elem()),
        base_step(1, "legacy", "PASS", None, command={"type": "LaunchAppCommand"}),
    ]
    write_jsonl(a, steps)
    write_jsonl(b, [dict(s, backendId="stock") for s in steps])

    proc = run_diff(a, b)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["divergences"] == []
    assert result["firstDivergentStep"] is None
    assert result["stepsCompared"] == 2


# ---------------------------------------------------------------------------
# 2. centerX differs by 1 with tol=2 -> 0 divergences (within tolerance)
# ---------------------------------------------------------------------------
def test_coordinate_within_tolerance_no_divergence(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem(center_x=60))])
    write_jsonl(b, [base_step(0, "stock", "PASS", elem(center_x=61))])

    proc = run_diff(a, b, tol=2)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["divergences"] == []


# ---------------------------------------------------------------------------
# 3. centerX differs by 3 with tol=2 -> 1 coordinate divergence
# ---------------------------------------------------------------------------
def test_coordinate_beyond_tolerance_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem(center_x=60))])
    write_jsonl(b, [base_step(0, "stock", "PASS", elem(center_x=63))])

    proc = run_diff(a, b, tol=2)
    assert proc.returncode != 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert len(result["divergences"]) == 1
    assert result["divergences"][0]["kind"] == "coordinate"
    assert result["divergences"][0]["stepIndex"] == 0
    assert result["firstDivergentStep"] == 0


# ---------------------------------------------------------------------------
# 4. Different verdict -> verdict divergence
# ---------------------------------------------------------------------------
def test_verdict_mismatch_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    write_jsonl(b, [base_step(0, "stock", "FAIL", elem())])

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "verdict" in kinds
    assert result["firstDivergentStep"] == 0


# ---------------------------------------------------------------------------
# 5. Element present vs absent -> element-presence divergence
# ---------------------------------------------------------------------------
def test_element_presence_mismatch_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    write_jsonl(b, [base_step(0, "stock", "PASS", None)])

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "element-presence" in kinds


# ---------------------------------------------------------------------------
# 6. Different resourceId -> element-identity divergence
# ---------------------------------------------------------------------------
def test_element_identity_mismatch_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem(resource_id="com.example:id/login"))])
    write_jsonl(b, [base_step(0, "stock", "PASS", elem(resource_id="com.example:id/other"))])

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "element-identity" in kinds


def test_element_identity_text_mismatch_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem(text="Login"))])
    write_jsonl(b, [base_step(0, "stock", "PASS", elem(text="Log in"))])

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "element-identity" in kinds


# ---------------------------------------------------------------------------
# 7. A has 5 steps, B has 4 -> step-count divergence at index 4
# ---------------------------------------------------------------------------
def test_step_count_mismatch_diverges_at_missing_index(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    a_steps = [base_step(i, "legacy", "PASS", elem()) for i in range(5)]
    b_steps = [base_step(i, "stock", "PASS", elem()) for i in range(4)]
    write_jsonl(a, a_steps)
    write_jsonl(b, b_steps)

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "step-count" in kinds
    step_count_divs = [d for d in result["divergences"] if d["kind"] == "step-count"]
    assert step_count_divs[0]["stepIndex"] == 4
    assert result["firstDivergentStep"] == 4
    # steps 0-3 matched fine; only the missing one diverges
    assert len(result["divergences"]) == 1


# ---------------------------------------------------------------------------
# 8. declined:true step -> coverage gap, NOT a divergence
# ---------------------------------------------------------------------------
def test_declined_step_is_coverage_gap_not_divergence(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    declined_step = base_step(0, "stock", "PASS", None, declined=True, declinedReason="unsupported-selector")
    write_jsonl(b, [declined_step])

    proc = run_diff(a, b)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["divergences"] == []
    assert result["firstDivergentStep"] is None
    assert len(result["coverageGaps"]) == 1
    gap = result["coverageGaps"][0]
    assert gap["stepIndex"] == 0
    assert gap["backend"] == "stock"
    assert gap["command"] == "TapOnElementCommand"


# ---------------------------------------------------------------------------
# 9. Corpus aggregate over 2 flows (one clean, one divergent) -> correct
#    summary + non-zero exit.
# ---------------------------------------------------------------------------
def test_corpus_aggregate_two_flows_one_divergent(tmp_path):
    corpus = tmp_path / "corpus"
    clean_flow = corpus / "flow-clean"
    div_flow = corpus / "flow-divergent"
    (clean_flow / "a").mkdir(parents=True)
    (clean_flow / "b").mkdir(parents=True)
    (div_flow / "a").mkdir(parents=True)
    (div_flow / "b").mkdir(parents=True)

    clean_steps = [base_step(0, "legacy", "PASS", elem())]
    write_jsonl(clean_flow / "a" / "steps.jsonl", clean_steps)
    write_jsonl(clean_flow / "b" / "steps.jsonl", [dict(s, backendId="stock") for s in clean_steps])

    write_jsonl(div_flow / "a" / "steps.jsonl", [base_step(0, "legacy", "PASS", elem())])
    write_jsonl(div_flow / "b" / "steps.jsonl", [base_step(0, "stock", "FAIL", elem())])

    proc = run_corpus(corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["totalFlows"] == 2
    assert result["flowsZeroDivergence"] == 1
    assert result["flowsWithDivergence"] == 1
    assert result["divergenceCountsByKind"].get("verdict") == 1


def test_corpus_aggregate_all_clean_exits_zero(tmp_path):
    corpus = tmp_path / "corpus"
    flow = corpus / "flow-only"
    (flow / "a").mkdir(parents=True)
    (flow / "b").mkdir(parents=True)
    steps = [base_step(0, "legacy", "PASS", elem())]
    write_jsonl(flow / "a" / "steps.jsonl", steps)
    write_jsonl(flow / "b" / "steps.jsonl", [dict(s, backendId="stock") for s in steps])

    proc = run_corpus(corpus)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["totalFlows"] == 1
    assert result["flowsZeroDivergence"] == 1
    assert result["flowsWithDivergence"] == 0


# ---------------------------------------------------------------------------
# Robustness: missing/malformed files are a clear error, not a silent pass.
# ---------------------------------------------------------------------------
def test_missing_file_is_error_not_silent_pass(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    a.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    missing_b = tmp_path / "b" / "steps.jsonl"  # never created

    proc = run_diff(a, missing_b)
    assert proc.returncode != 0
    assert proc.returncode != 0 and "not" in (proc.stderr.lower() + proc.stdout.lower()) or proc.stderr


def test_empty_file_is_error_not_silent_pass(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    a.write_text("")
    b.write_text("")

    proc = run_diff(a, b)
    assert proc.returncode != 0, "empty trace files must not silently pass"


def test_malformed_json_line_is_error(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    a.write_text('{"stepIndex":0,"backendId":"legacy"\n')  # truncated / invalid JSON
    write_jsonl(b, [base_step(0, "stock", "PASS", elem())])

    proc = run_diff(a, b)
    assert proc.returncode != 0


# ---------------------------------------------------------------------------
# Determinism: divergences sorted by stepIndex regardless of write order
# is implicitly covered since inputs are always written in order; add an
# explicit multi-divergence ordering check.
# ---------------------------------------------------------------------------
def test_divergences_sorted_by_step_index(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    a_steps = [
        base_step(0, "legacy", "PASS", elem()),
        base_step(1, "legacy", "PASS", elem()),
        base_step(2, "legacy", "FAIL", elem()),
    ]
    b_steps = [
        base_step(0, "stock", "FAIL", elem()),
        base_step(1, "stock", "PASS", elem()),
        base_step(2, "stock", "PASS", elem()),
    ]
    write_jsonl(a, a_steps)
    write_jsonl(b, b_steps)

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    indices = [d["stepIndex"] for d in result["divergences"]]
    assert indices == sorted(indices)
    assert result["firstDivergentStep"] == 0
