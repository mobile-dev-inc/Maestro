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
# 8. A step whose verdict is ERROR with error.type BackendUnsupportedOperation
#    (the "gap" bucket) -> coverage gap, NOT a divergence.
# ---------------------------------------------------------------------------
def test_gap_error_step_is_coverage_gap_not_divergence(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    gap_step = base_step(
        0, "stock", "ERROR", None,
        error={"type": "BackendUnsupportedOperation", "message": "device-core has no verb for TapOnElementCommand"},
    )
    write_jsonl(b, [gap_step])

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
    assert gap["errorType"] == "BackendUnsupportedOperation"


# ---------------------------------------------------------------------------
# 8b. A step whose verdict is ERROR with error.type DeviceCoreUnavailable
#     (the "infra" bucket) -> ALSO a coverage gap, not a divergence, but
#     tagged with the infra error type so downstream readers (fidelity_report)
#     can tell it apart from a not-yet-built-verb gap.
# ---------------------------------------------------------------------------
def test_infra_error_step_is_coverage_gap_with_infra_error_type(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    infra_step = base_step(
        0, "stock", "ERROR", None,
        error={"type": "DeviceCoreUnavailable", "message": "device-core server unreachable"},
    )
    write_jsonl(b, [infra_step])

    proc = run_diff(a, b)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    assert result["divergences"] == []
    assert len(result["coverageGaps"]) == 1
    assert result["coverageGaps"][0]["errorType"] == "DeviceCoreUnavailable"


# ---------------------------------------------------------------------------
# 8c. An ERROR step with no recognized error.type (or none at all) is a
#     genuine verdict divergence, not silently swallowed as a gap.
# ---------------------------------------------------------------------------
def test_unrecognized_error_type_is_a_verdict_divergence(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()
    write_jsonl(a, [base_step(0, "legacy", "PASS", elem())])
    write_jsonl(b, [base_step(0, "stock", "ERROR", elem(), error={"type": "SomeOtherException", "message": "boom"})])

    proc = run_diff(a, b)
    assert proc.returncode != 0
    result = json.loads(proc.stdout)
    assert result["coverageGaps"] == []
    kinds = [d["kind"] for d in result["divergences"]]
    assert "verdict" in kinds


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
    assert "not found" in proc.stderr.lower()


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
# Fix 1 regression: a REQUIRED coordinate field missing on one side must be
# reported as a coordinate divergence, not silently skipped (a serialization
# bug dropping e.g. centerY must not produce a false green).
# ---------------------------------------------------------------------------
def test_missing_required_coordinate_field_diverges(tmp_path):
    a = tmp_path / "a" / "steps.jsonl"
    b = tmp_path / "b" / "steps.jsonl"
    a.parent.mkdir()
    b.parent.mkdir()

    a_elem = elem()
    b_elem = elem()
    del b_elem["centerY"]  # simulate a backend serialization bug

    write_jsonl(a, [base_step(0, "legacy", "PASS", a_elem)])
    write_jsonl(b, [base_step(0, "stock", "PASS", b_elem)])

    proc = run_diff(a, b)
    assert proc.returncode != 0, proc.stdout + proc.stderr
    result = json.loads(proc.stdout)
    kinds = [d["kind"] for d in result["divergences"]]
    assert "coordinate" in kinds
    coord_div = next(d for d in result["divergences"] if d["kind"] == "coordinate")
    assert coord_div["stepIndex"] == 0
    assert coord_div["a"]["centerY"] == 40
    assert coord_div["b"]["centerY"] is None


# ---------------------------------------------------------------------------
# Fix 2: --corpus error paths must fail loudly (non-zero exit), never a
# silent 0/green — these are the gate-verdict paths.
# ---------------------------------------------------------------------------
def test_corpus_nonexistent_dir_is_error(tmp_path):
    missing_corpus = tmp_path / "does-not-exist"
    proc = run_corpus(missing_corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr
    assert "not found" in proc.stderr.lower()


def test_corpus_empty_dir_no_flows_is_error(tmp_path):
    empty_corpus = tmp_path / "empty-corpus"
    empty_corpus.mkdir()
    proc = run_corpus(empty_corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr


def test_corpus_flow_missing_a_side_is_error(tmp_path):
    corpus = tmp_path / "corpus"
    flow = corpus / "flow-missing-a"
    (flow / "b").mkdir(parents=True)
    # flow/a/steps.jsonl is never created
    write_jsonl(flow / "b" / "steps.jsonl", [base_step(0, "stock", "PASS", elem())])

    proc = run_corpus(corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr


def test_corpus_flow_missing_b_side_is_error(tmp_path):
    corpus = tmp_path / "corpus"
    flow = corpus / "flow-missing-b"
    (flow / "a").mkdir(parents=True)
    # flow/b/steps.jsonl is never created
    write_jsonl(flow / "a" / "steps.jsonl", [base_step(0, "legacy", "PASS", elem())])

    proc = run_corpus(corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr


def test_corpus_one_bad_flow_fails_whole_corpus_not_silently_dropped(tmp_path):
    # A good flow alongside a flow with a missing trace file: the corpus run
    # must fail overall, not silently drop the bad flow and report on the
    # good one alone.
    corpus = tmp_path / "corpus"
    good_flow = corpus / "flow-good"
    bad_flow = corpus / "flow-bad"
    (good_flow / "a").mkdir(parents=True)
    (good_flow / "b").mkdir(parents=True)
    (bad_flow / "a").mkdir(parents=True)
    # bad_flow/b/steps.jsonl never created

    good_steps = [base_step(0, "legacy", "PASS", elem())]
    write_jsonl(good_flow / "a" / "steps.jsonl", good_steps)
    write_jsonl(good_flow / "b" / "steps.jsonl", [dict(s, backendId="stock") for s in good_steps])
    write_jsonl(bad_flow / "a" / "steps.jsonl", [base_step(0, "legacy", "PASS", elem())])

    proc = run_corpus(corpus)
    assert proc.returncode != 0, proc.stdout + proc.stderr


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
