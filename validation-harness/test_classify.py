#!/usr/bin/env python3
"""Tests for classify.py — the control-subtracted, flakiness-robust gate."""
import classify


def step(i, verdict="PASS", text=None, rid=None, cx=None, cy=None, elem=True):
    o = {"stepIndex": i, "verdict": verdict, "command": {"type": "X"}}
    if elem:
        o["chosenElement"] = {"text": text, "resourceId": rid,
                              "centerX": cx, "centerY": cy,
                              "x": cx, "y": cy, "width": 10, "height": 10}
    else:
        o["chosenElement"] = None
    return o


def steps(*objs):
    return {o["stepIndex"]: o for o in objs}


# ── both backends fully reproducible + identical → GREEN, all judged ───────
def test_all_identical_is_green():
    s = steps(step(0, text="a", cx=5, cy=5), step(1, "PASS", text="b", cx=7, cy=7))
    l = steps(step(0, text="a", cx=5, cy=5), step(1, "PASS", text="b", cx=7, cy=7))
    r = classify.classify_flow([s, s], [l, l])
    assert r["green"] is True
    assert r["kFlaky"] is None
    assert r["judgedSteps"] == 2
    assert r["excludedSteps"] == 0


# ── legacy picks a DIFFERENT element where both backends reproducible → RED ─
def test_real_identity_divergence_is_red():
    s = steps(step(0, text="a"), step(1, text="right"))
    l = steps(step(0, text="a"), step(1, text="WRONG"))
    r = classify.classify_flow([s, s], [l, l])
    assert r["green"] is False
    assert r["realDivergenceStep"] == 1
    assert r["realDivergenceDetail"]["kind"] == "behavioral"


# ── the CompanyCam trap: coin-flip step, stock agrees by luck, legacy differs.
#    Legacy's own two runs flip-flop → step excluded → NOT a false RED. ──────
def test_coinflip_caught_by_legacy_reproducibility():
    # both stock runs FAIL the flaky assert at step 1 (agree by luck)
    s = steps(step(0, "PASS"), step(1, "FAIL", elem=False))
    # legacy flip-flops at step 1: one run PASS(loaded), one run FAIL(not loaded)
    l1 = steps(step(0, "PASS"), step(1, "PASS", rid="btn"), step(2, "PASS"))
    l2 = steps(step(0, "PASS"), step(1, "FAIL", elem=False))
    r = classify.classify_flow([s, s], [l1, l2])
    assert r["kFlaky"] == 1          # legacy flaky at 1 → excluded from here
    assert r["judgedSteps"] == 1     # only step 0
    assert r["green"] is True        # no false RED


# ── stock flaky step also excludes downstream ──────────────────────────────
def test_stock_flaky_step_excludes_downstream():
    s1 = steps(step(0), step(1, "PASS", text="btn"), step(2))
    s2 = steps(step(0), step(1, "FAIL", elem=False))   # stock flaky at 1
    l = steps(step(0), step(1, "PASS", text="btn"), step(2), step(3))
    r = classify.classify_flow([s1, s2], [l, l])
    assert r["kFlaky"] == 1
    assert r["judgedSteps"] == 1
    assert r["green"] is True


# ── scroll noise: same identity, different pixel → GREEN behaviorally ───────
def test_coordinate_noise_is_green_behaviorally():
    s1 = steps(step(0, text="Feature 10", cx=100, cy=500))
    s2 = steps(step(0, text="Feature 10", cx=100, cy=696))
    l1 = steps(step(0, text="Feature 10", cx=100, cy=550))
    l2 = steps(step(0, text="Feature 10", cx=100, cy=600))
    r = classify.classify_flow([s1, s2], [l1, l2])
    assert r["green"] is True
    assert r["coordFlags"] == []     # stock not rock-stable → no flag


# ── coord flag: stock rock-stable, legacy moved → reported, not gating ──────
def test_coordinate_flag_when_stock_stable():
    s1 = steps(step(0, text="btn", cx=100, cy=200))
    s2 = steps(step(0, text="btn", cx=101, cy=200))   # stock stable (≤2px)
    l1 = steps(step(0, text="btn", cx=100, cy=260))   # legacy moved 60px
    l2 = steps(step(0, text="btn", cx=100, cy=260))
    r = classify.classify_flow([s1, s2], [l1, l2])
    assert r["green"] is True
    assert len(r["coordFlags"]) == 1


# ── legacy stops early where stock reproducibly continues → RED ────────────
def test_legacy_missing_reproduced_step_is_red():
    s = steps(step(0), step(1), step(2))
    l = steps(step(0), step(1))           # legacy reproducibly stops at 2
    r = classify.classify_flow([s, s], [l, l])
    assert r["green"] is False
    assert r["realDivergenceStep"] == 2
    assert r["realDivergenceDetail"]["kind"] == "presence-or-step-count"


# ── presence divergence where both reproducible → RED ──────────────────────
def test_presence_divergence_is_red():
    s = steps(step(0, text="x"))
    l = steps(step(0, elem=False))
    r = classify.classify_flow([s, s], [l, l])
    assert r["green"] is False
    assert r["realDivergenceStep"] == 0


# ── single stock + single legacy still works (triple-style, 1 legacy) ───────
def test_single_run_each():
    s = steps(step(0, text="a"))
    l = steps(step(0, text="a"))
    r = classify.classify_flow([s], [l])
    assert r["green"] is True
    assert r["judgedSteps"] == 1
