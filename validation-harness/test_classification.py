# test_classification.py
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
