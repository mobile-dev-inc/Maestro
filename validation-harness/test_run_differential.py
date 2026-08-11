import json, os, textwrap
import pytest
import run_differential
from run_differential import run_one_folder, BACKENDS

class FakeExecutor:
    def __init__(self): self.calls = []; self.booted = 0
    def boot(self, spec, device_bin, timeout=360):
        self.booted += 1
        from executor import DeviceHandle
        return DeviceHandle("emulator-1", spec["platform"], "full", object(), "/tmp/log")
    def teardown(self, h): self.calls.append(("teardown", h.device_id))
    def sh(self, script, timeout=None, check=True):
        self.calls.append(("sh", script))
        class R: stdout = "/x/legacy-out/f/trace/steps.jsonl"; returncode = 0
        return R()
    def put(self, l, r, timeout=None): self.calls.append(("put", l, r))
    def get(self, r, l, timeout=None):
        self.calls.append(("get", r, l))
        os.makedirs(os.path.dirname(l), exist_ok=True)
        with open(l, "w") as fh: fh.write(json.dumps({"stepIndex":0,"backendId":"x","command":{"type":"LaunchAppCommand"},"verdict":"PASS"})+"\n")
        return True

def _android_spec(tmp_path):
    from run_folder import read_run_folder
    d = tmp_path / "run_x"
    (d / "workspace" / "flows").mkdir(parents=True)
    (d / "workspace" / "flows" / "f.yaml").write_text("appId: com.x\n---\n- launchApp\n")
    (d / "app.apk").write_text("apk")
    (d / "metadata.json").write_text(json.dumps({
        "run_id":"run_x","platform":"ANDROID","package_id":"com.x",
        "device_spec":{"model":"pixel_6","os":"android-34","locale":None},
        "env":{"K":"V"},"flow_file_path":"flows/f.yaml"}))
    return read_run_folder(str(d))

def test_backends_are_legacy_then_devicecore_with_env():
    assert [b[0] for b in BACKENDS] == ["legacy", "devicecore"]
    assert BACKENDS[0][1] == {}
    assert BACKENDS[1][1] == {"MAESTRO_DEVICECORE_ASSERT": "1"}

def test_boots_one_device_shared_by_both_backends(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    report = run_one_folder(ex, spec, cli="/x/maestro", out_dir=str(tmp_path/"out"),
                            video=False, device_bin="/x/fake")
    assert ex.booted == 1
    assert sum(1 for c in ex.calls if c[0] == "teardown") == 1
    resets = [c for c in ex.calls if c[0]=="sh" and ("pm clear" in c[1] or "terminate" in c[1])]
    assert len(resets) >= 2   # reset before EACH backend
    # device-core CLI invocation carries the env var; legacy does not
    dc_runs = [c[1] for c in ex.calls if c[0]=="sh" and "MAESTRO_DEVICECORE_ASSERT=1" in c[1] and "test" in c[1]]
    legacy_runs = [c[1] for c in ex.calls if c[0]=="sh" and "test" in c[1] and "--device" in c[1] and "MAESTRO_DEVICECORE_ASSERT=1" not in c[1]]
    assert len(dc_runs) == 1 and len(legacy_runs) == 1
    # per-run env passed to BOTH as -e K=V
    assert all("-e" in r and "K=V" in r for r in dc_runs + legacy_runs)
    # outputs written
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"diff.json"))
    assert report["runId"] == "run_x" and report["platform"] == "ANDROID"

def test_one_bad_folder_does_not_abort(tmp_path, monkeypatch):
    # main() over a bad folder path + a good one still writes report.json
    spec = _android_spec(tmp_path)
    # point main at a nonexistent glob + the good folder; monkeypatch executor + _pull_trace
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: FakeExecutor().get("r", local))
    monkeypatch.setattr(run_differential, "LocalExecutor", FakeExecutor)
    rc = run_differential.main(["--executor","local","--cli","/x/maestro",
        "--out", str(tmp_path/"out2"), spec.run_dir, str(tmp_path/"does-not-exist-*")])
    assert rc == 0
    assert os.path.exists(str(tmp_path/"out2"/"report.json"))
