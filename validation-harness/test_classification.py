# test_classification.py
import json
import os

import classification


def _fr(steps):
    """Minimal fidelity_report-shaped dict."""
    return {"steps": steps}


def _step(i, status, cmd="TapOnElementCommand", etype=None, emsg=None):
    return {"stepIndex": i, "command": cmd, "status": status,
            "twoxVerdict": "PASS", "threexVerdict": "FAIL",
            "errorType": etype, "errorMessage": emsg}


def test_wall_only_run_is_capability_gap_not_genuine():
    # The skyscanner/kraken case: assertNotVisible/waitFor(GONE) is a diverge=0
    # WALL, not a fidelity divergence. This is the by-eye mistake the classifier
    # must prevent (spec exit-check 1).
    diff = _fr([
        _step(0, "AGREE", "LaunchAppCommand"),
        _step(1, "OWED", "AssertConditionCommand", etype="NotImplemented", emsg="assertNotVisible"),
        _step(2, "NOT_REACHED", "TapOnElementCommand"),
    ])
    assert classification.first_real_divergence(diff) is None
    assert classification.bucket_for(diff) == "capability-gap"


def test_all_agree_run_is_none():
    diff = _fr([_step(0, "AGREE"), _step(1, "AGREE")])
    assert classification.bucket_for(diff) == "none"


def test_text_ambiguity_diverge_is_genuine_fidelity():
    # wahed/newcore class: a real DIVERGE with no infrastructural cause.
    diff = _fr([
        _step(0, "AGREE", "LaunchAppCommand"),
        _step(1, "DIVERGE", "TapOnElementCommand", emsg="Element not actionable: .*Welcome.*"),
    ])
    frd = classification.first_real_divergence(diff)
    assert frd["stepIndex"] == 1
    assert classification.bucket_for(diff) == "genuine-fidelity"


def test_diverge_before_a_later_wall_wins():
    diff = _fr([
        _step(0, "DIVERGE", emsg="real divergence"),
        _step(1, "OWED", etype="NotImplemented", emsg="setLocation"),
    ])
    assert classification.bucket_for(diff) == "genuine-fidelity"


def test_setpermission_strategy_gap_bucket():
    diff = _fr([_step(1, "DIVERGE", "SetPermissionsCommand",
                      emsg="setPermission strategy 'unset' not implemented")])
    assert classification.bucket_for(diff) == "strategy-gap"


def test_clearcache_api_mismatch_is_env_mismatch():
    diff = _fr([_step(1, "DIVERGE", "ClearStateCommand",
                      emsg="clearCache failed: API 34 behavior on API 33 device")])
    assert classification.bucket_for(diff) == "env-mismatch"


def test_classify_run_shape_and_signature():
    diff = _fr([_step(0, "AGREE"), _step(1, "DIVERGE", emsg="not actionable")])
    r = classification.classify_run(diff, "run_wahed", "com.wahed")
    assert r == {
        "runId": "run_wahed", "package": "com.wahed", "bucket": "genuine-fidelity",
        "firstDivergence": {"stepIndex": 1, "command": "TapOnElementCommand",
                            "status": "DIVERGE", "errorType": None,
                            "errorMessage": "not actionable"},
        "signature": ["com.wahed", "not actionable"],
    }


def test_signature_for_no_divergence():
    diff = _fr([_step(0, "AGREE")])
    assert classification.signature_for("com.x", diff) == ["com.x", "<no-divergence>"]


def test_classify_corpus_dedupes_by_surface_signature():
    # Same app run twice with the same first-divergence message -> ONE group,
    # two runIds. Different message -> a separate group (wahed vs newcore carry
    # different surface messages even when they share a root cause; root-cause
    # dedupe is a post-triage reporting step, NOT here).
    diff_a = _fr([_step(1, "DIVERGE", emsg="not actionable")])
    diff_b = _fr([_step(1, "DIVERGE", emsg="not actionable")])
    diff_c = _fr([_step(1, "DIVERGE", emsg="not visible")])
    out = classification.classify_corpus([
        {"runId": "run_1", "package": "com.wahed", "diff": diff_a},
        {"runId": "run_2", "package": "com.wahed", "diff": diff_b},
        {"runId": "run_3", "package": "com.newcore", "diff": diff_c},
    ])
    assert len(out["runs"]) == 3
    genuine = [g for g in out["groups"] if g["bucket"] == "genuine-fidelity"]
    by_sig = {tuple(g["signature"]): g for g in genuine}
    assert by_sig[("com.wahed", "not actionable")]["runIds"] == ["run_1", "run_2"]
    assert by_sig[("com.newcore", "not visible")]["runIds"] == ["run_3"]


def test_classify_corpus_groups_wall_runs_separately_from_genuine():
    wall = _fr([_step(0, "OWED", etype="NotImplemented", emsg="setLocation")])
    real = _fr([_step(0, "DIVERGE", emsg="not actionable")])
    out = classification.classify_corpus([
        {"runId": "w", "package": "com.a", "diff": wall},
        {"runId": "r", "package": "com.b", "diff": real},
    ])
    buckets = {g["bucket"] for g in out["groups"]}
    assert buckets == {"capability-gap", "genuine-fidelity"}


def _write_diff(out_dir, run_id, steps):
    d = os.path.join(out_dir, run_id)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "diff.json"), "w") as fh:
        json.dump({"steps": steps}, fh)


def test_write_classification_single_and_batch_are_identical(tmp_path):
    # Same diff.json set, two different tree layouts (single: out/; batch:
    # work/out/). classification.json content MUST be byte-identical — the core
    # invariant that remote just runs the local unit.
    steps_wahed = [_step(0, "AGREE"), _step(1, "DIVERGE", emsg="not actionable")]
    steps_komoot = [_step(0, "AGREE"), _step(1, "OWED", etype="NotImplemented", emsg="setLocation")]
    aggregate = {"folders": [
        {"runId": "run_wahed", "package": "com.wahed", "status": "ok"},
        {"runId": "run_komoot", "package": "com.komoot", "status": "ok"},
        {"runId": "run_broken", "package": "com.x", "status": "incomplete"},
    ]}

    single = tmp_path / "out"
    _write_diff(str(single), "run_wahed", steps_wahed)
    _write_diff(str(single), "run_komoot", steps_komoot)
    single_dest = str(tmp_path / "out" / "classification.json")
    classification.write_classification(str(single), aggregate, single_dest)

    batch = tmp_path / "work" / "out"
    _write_diff(str(batch), "run_wahed", steps_wahed)
    _write_diff(str(batch), "run_komoot", steps_komoot)
    batch_dest = str(tmp_path / "work" / "classification.json")
    classification.write_classification(str(batch), aggregate, batch_dest)

    assert open(single_dest).read() == open(batch_dest).read()
    data = json.load(open(single_dest))
    by_run = {r["runId"]: r for r in data["runs"]}
    assert by_run["run_wahed"]["bucket"] == "genuine-fidelity"
    assert by_run["run_komoot"]["bucket"] == "capability-gap"
    assert "run_broken" not in by_run   # non-ok folders excluded
