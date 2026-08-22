import json, os, textwrap
import pytest
import run_differential
from run_differential import run_one_folder, SIDES

class FakeExecutor:
    # 1st boot (2x) reports a "full" spec match; 2nd boot (3x) reports "approx"
    # (e.g. a locale fallback) — deliberately different so a test can prove the
    # two sides' spec_fidelity readings are recorded independently, not
    # last-write-wins.
    _FIDELITY_BY_BOOT = ["full", "approx"]

    def __init__(self): self.calls = []; self.booted = 0
    def boot(self, spec, device_bin, timeout=360):
        self.booted += 1
        from executor import DeviceHandle
        fidelity = self._FIDELITY_BY_BOOT[(self.booted - 1) % len(self._FIDELITY_BY_BOOT)]
        return DeviceHandle(f"emulator-{self.booted}", spec["platform"], fidelity, object(), "/tmp/log")
    def teardown(self, h): self.calls.append(("teardown", h.device_id))
    def sh(self, script, timeout=None, check=True):
        self.calls.append(("sh", script))
        class R: stdout = "/x/2x-out/f/trace/steps.jsonl"; returncode = 0
        return R()
    def put(self, l, r, timeout=None): self.calls.append(("put", l, r))
    def get(self, r, l, timeout=None):
        self.calls.append(("get", r, l))
        os.makedirs(os.path.dirname(l), exist_ok=True)
        with open(l, "w") as fh: fh.write(json.dumps({"stepIndex":0,"backendId":"x","command":{"type":"LaunchAppCommand"},"verdict":"PASS"})+"\n")
        return True

def _android_folder(tmp_path, name="run_x"):
    d = tmp_path / name
    (d / "workspace" / "flows").mkdir(parents=True)
    (d / "workspace" / "flows" / "f.yaml").write_text("appId: com.x\n---\n- launchApp\n")
    (d / "app.apk").write_text("apk")
    (d / "metadata.json").write_text(json.dumps({
        "run_id":name,"platform":"ANDROID","package_id":"com.x",
        "device_spec":{"model":"pixel_6","os":"android-34","locale":None},
        "env":{"K":"V"},"flow_file_path":"flows/f.yaml"}))
    return str(d)

def _android_spec(tmp_path, name="run_x"):
    from run_folder import read_run_folder
    return read_run_folder(_android_folder(tmp_path, name))

def test_sides_are_2x_and_3x():
    assert SIDES == ["2x", "3x"]

def test_runs_each_side_on_its_own_fresh_device(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    report = run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                            video=False, device_bin="/x/fake")

    # two boots (one clean device per side) and two teardowns
    assert ex.booted == 2
    assert sum(1 for c in ex.calls if c[0] == "teardown") == 2

    scripts = [c[1] for c in ex.calls if c[0] == "sh"]
    resets = [s for s in scripts if "pm clear" in s or "terminate" in s]
    assert len(resets) >= 2   # reset before EACH side

    # each side's CLI referenced by its own path
    twox_runs = [s for s in scripts if "/2x" in s and "test" in s]
    threex_runs = [s for s in scripts if "/3x" in s and "test" in s]
    assert len(twox_runs) == 1 and len(threex_runs) == 1

    # per-run env passed to BOTH as -e K=V
    assert all("-e" in r and "K=V" in r for r in twox_runs + threex_runs)

    # no MAESTRO_DEVICECORE_ASSERT anywhere — the env-toggle mechanism is gone
    assert all("MAESTRO_DEVICECORE_ASSERT" not in s for s in scripts)

    # ANDROID_SERIAL is exported for both sides with THAT side's own device_id
    assert any("ANDROID_SERIAL=emulator-1" in r for r in twox_runs + threex_runs)
    assert any("ANDROID_SERIAL=emulator-2" in r for r in twox_runs + threex_runs)

    # outputs written — full layout
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"diff.json"))
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"2x"/"steps.jsonl"))
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"3x"/"steps.jsonl"))

    # returned report dict carries the documented keys
    for key in ("runId","platform","fidelityGreen","served","agree","diverge","owed"):
        assert key in report
    assert report["runId"] == "run_x" and report["platform"] == "ANDROID"

    # specFidelity is recorded PER SIDE and independently — the 2x (oracle)
    # boot's fidelity must survive even though 3x boots afterward with a
    # different reading (last-write-wins would silently hide a degraded
    # oracle boot behind the 3x side's "full"/"approx" value).
    assert "specFidelity" not in report
    assert report["specFidelity2x"] == "full"
    assert report["specFidelity3x"] == "approx"

def test_one_bad_folder_does_not_abort(tmp_path, monkeypatch):
    # TWO good, EXPANDED folders; the FIRST raises INSIDE the main loop. The run
    # must still complete: rc==0, report.json written, with BOTH a failed-folder
    # entry (status "error" + message) AND the successful one. This exercises the
    # loop's except-continue branch — a nonexistent glob would be filtered out by
    # expand_folders before the loop and prove nothing.
    bad = _android_folder(tmp_path, "run_bad")
    good = _android_folder(tmp_path, "run_good")
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: FakeExecutor().get("r", local))
    monkeypatch.setattr(run_differential, "LocalExecutor", FakeExecutor)

    real = run_differential.run_one_folder
    def flaky(executor, spec, **kw):
        if spec.run_id == "run_bad":
            raise RuntimeError("boom on run_bad")
        return real(executor, spec, **kw)
    monkeypatch.setattr(run_differential, "run_one_folder", flaky)

    rc = run_differential.main(["--executor","local","--cli-2x","/2x","--cli-3x","/3x",
        "--out", str(tmp_path/"out2"), bad, good])
    assert rc == 0
    report_path = str(tmp_path/"out2"/"report.json")
    assert os.path.exists(report_path)
    with open(report_path) as fh:
        agg = json.load(fh)
    by_id = {f.get("runId"): f for f in agg["folders"]}
    assert by_id["run_bad"]["status"] == "error"
    assert "boom on run_bad" in by_id["run_bad"]["error"]
    assert by_id["run_good"]["status"] == "ok"
