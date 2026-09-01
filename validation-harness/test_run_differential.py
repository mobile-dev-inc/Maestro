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

def test_source_and_provenance_written_when_manifest_binaries_given(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    monkeypatch.setattr(run_differential, "_pull_log", lambda e, d, l: False)
    binaries = [{"role": "3x", "contentHash": "sha256:aaa"},
                {"role": "2x", "contentHash": "sha256:bbb"}]
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake", manifest_binaries=binaries)
    src = json.load(open(tmp_path/"out"/"run_x"/"source.json"))
    prov = json.load(open(tmp_path/"out"/"run_x"/"provenance.json"))
    assert src["corpusPath"] == spec.run_dir
    assert src["appContentHash"].startswith("sha256:")
    assert {b["contentHash"] for b in prov["binaries"]} == {"sha256:aaa", "sha256:bbb"}

def test_flow_dir_copied_scrubbed(tmp_path, monkeypatch):
    # A folder whose flow embeds the per-run env secret value; the copied flow/
    # must have it redacted (spec exit-check 8: zero corpus tokens in the bundle).
    d = tmp_path / "run_s"
    (d / "workspace" / "flows").mkdir(parents=True)
    (d / "workspace" / "flows" / "f.yaml").write_text(
        "appId: com.x\n---\n- launchApp\n- inputText: sk-secret-123\n")
    (d / "app.apk").write_text("apk")
    (d / "metadata.json").write_text(json.dumps({
        "run_id": "run_s", "platform": "ANDROID", "package_id": "com.x",
        "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None},
        "env": {"TOKEN": "sk-secret-123"}, "flow_file_path": "flows/f.yaml"}))
    from run_folder import read_run_folder
    spec = read_run_folder(str(d))
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    monkeypatch.setattr(run_differential, "_pull_log", lambda e, d, l: False)
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake")
    flow_dir = tmp_path/"out"/"run_s"/"flow"
    assert flow_dir.exists()
    blob = "".join(open(os.path.join(r, f)).read()
                   for r, _, fs in os.walk(flow_dir) for f in fs)
    assert "sk-secret-123" not in blob
    assert "***REDACTED***" in blob

def test_provenance_skipped_when_no_manifest_binaries(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    monkeypatch.setattr(run_differential, "_pull_log", lambda e, d, l: False)
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake")
    # source.json is corpus-provenance, written regardless; provenance.json needs
    # the manifest and is skipped (backward compatible) when it is absent.
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"source.json"))
    assert not os.path.exists(str(tmp_path/"out"/"run_x"/"provenance.json"))

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

def test_device_serial_skips_boot_and_teardown_and_reuses_device(tmp_path, monkeypatch):
    # --device <serial> is the local fast-loop path: reuse an ALREADY-booted
    # device for BOTH sides, skipping boot/teardown entirely (never kill a
    # device the harness didn't start). Both sides' CLI invocations must
    # reference the given serial, and both traces must still be produced.
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    report = run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                            video=False, device_bin="/x/fake", device_serial="emulator-9999")

    # boot/teardown must NEVER be called — the device is already running and
    # is not owned by this harness invocation.
    assert ex.booted == 0
    assert sum(1 for c in ex.calls if c[0] == "teardown") == 0

    scripts = [c[1] for c in ex.calls if c[0] == "sh"]
    twox_runs = [s for s in scripts if "/2x" in s and "test" in s]
    threex_runs = [s for s in scripts if "/3x" in s and "test" in s]
    assert len(twox_runs) == 1 and len(threex_runs) == 1
    assert all("emulator-9999" in r for r in twox_runs + threex_runs)

    # reset still runs on the shared device for each side
    resets = [s for s in scripts if "pm clear" in s or "terminate" in s]
    assert len(resets) >= 2

    # outputs still written for both sides
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"2x"/"steps.jsonl"))
    assert os.path.exists(str(tmp_path/"out"/"run_x"/"3x"/"steps.jsonl"))

    assert report["specFidelity2x"] == "reused"
    assert report["specFidelity3x"] == "reused"

def test_run_one_folder_removes_each_sides_tmp_work_base(tmp_path, monkeypatch):
    # Each side's /tmp/rundiff-<runId>-<side> work base is removed after the side
    # finishes (results already pulled into out/) so it doesn't accumulate.
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake")
    scripts = [c[1] for c in ex.calls if c[0] == "sh"]
    assert any("rm -rf" in s and "/tmp/rundiff-run_x-2x" in s for s in scripts)
    assert any("rm -rf" in s and "/tmp/rundiff-run_x-3x" in s for s in scripts)


def test_run_one_folder_keep_scratch_leaves_the_work_base(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake", keep_scratch=True)
    scripts = [c[1] for c in ex.calls if c[0] == "sh"]
    assert not any("rm -rf" in s and "rundiff-run_x" in s for s in scripts)


def test_main_sweeps_leaked_sim_clones_at_end(tmp_path, monkeypatch):
    good = _android_folder(tmp_path, "run_good")
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: FakeExecutor().get("r", local))
    monkeypatch.setattr(run_differential, "LocalExecutor", FakeExecutor)
    called = []
    monkeypatch.setattr(run_differential, "sweep_ios_clones",
        lambda *a, **k: (called.append(True), [])[1])
    rc = run_differential.main(["--executor", "local", "--cli-2x", "/2x", "--cli-3x", "/3x",
        "--out", str(tmp_path/"o"), good])
    assert rc == 0
    assert called == [True]                # the leave-the-host-clean sweep fired


def test_main_keep_scratch_skips_the_sweep(tmp_path, monkeypatch):
    good = _android_folder(tmp_path, "run_good")
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: FakeExecutor().get("r", local))
    monkeypatch.setattr(run_differential, "LocalExecutor", FakeExecutor)
    called = []
    monkeypatch.setattr(run_differential, "sweep_ios_clones",
        lambda *a, **k: (called.append(True), [])[1])
    rc = run_differential.main(["--executor", "local", "--cli-2x", "/2x", "--cli-3x", "/3x",
        "--keep-scratch", "--out", str(tmp_path/"o"), good])
    assert rc == 0
    assert called == []                    # --keep-scratch leaves the host untouched


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


def test_main_emits_classification_json(tmp_path, monkeypatch):
    # The single-run call site (run_differential.main) must emit classification.json
    # alongside report.json, bucketed from the real per-run diff.json under the out
    # tree — the same core write_classification the batch path uses (A1c invariant).
    import classification
    good = _android_folder(tmp_path, "run_good")
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: FakeExecutor().get("r", local))
    monkeypatch.setattr(run_differential, "_pull_log", lambda e, d, l: False)
    monkeypatch.setattr(run_differential, "LocalExecutor", FakeExecutor)

    out = str(tmp_path / "out")
    rc = run_differential.main(["--executor", "local", "--cli-2x", "/2x",
                                "--cli-3x", "/3x", "--out", out, good])
    assert rc == 0

    cls_path = os.path.join(out, "classification.json")
    assert os.path.exists(cls_path)
    data = json.load(open(cls_path))
    by_run = {r["runId"]: r for r in data["runs"]}
    assert "run_good" in by_run

    # main's emitted bucket must match the core applied to the run's own diff.json
    diff = json.load(open(os.path.join(out, "run_good", "diff.json")))
    assert by_run["run_good"]["bucket"] == classification.bucket_for(diff)


def test_maestro_log_pulled_for_both_sides(tmp_path, monkeypatch):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    # _pull_log finds maestro.log under dbg and copies it; fake it to write a file.
    def fake_pull_log(executor, dbg, local):
        os.makedirs(os.path.dirname(local), exist_ok=True)
        with open(local, "w") as fh: fh.write("maestro log line\n")
        return True
    monkeypatch.setattr(run_differential, "_pull_log", fake_pull_log)
    run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x", out_dir=str(tmp_path/"out"),
                   video=False, device_bin="/x/fake")
    for side in SIDES:
        p = tmp_path/"out"/"run_x"/side/"maestro.log"
        assert os.path.exists(p) and os.path.getsize(p) > 0


def test_truncate_flow_keeps_header_and_first_n_commands():
    from run_differential import truncate_flow
    flow = ("appId: com.x\n---\n"
            "- launchApp\n- tapOn: Continue\n- inputText: hello\n- assertVisible: Home\n")
    out = truncate_flow(flow, 2)
    assert "appId: com.x" in out
    assert "launchApp" in out and "tapOn: Continue" in out
    assert "inputText" not in out and "assertVisible" not in out


def test_truncate_flow_n_ge_len_is_identity():
    from run_differential import truncate_flow
    flow = "appId: com.x\n---\n- launchApp\n- tapOn: Continue\n"
    # True identity: header + every trailing byte preserved, not just a count.
    assert truncate_flow(flow, 99) == flow


def test_truncate_flow_counts_trailing_bare_dash_command():
    from run_differential import truncate_flow
    # A bare `-` top-level command with a block child on the following lines,
    # and NO trailing EOF newline. It must count as a top-level command.
    flow = ("appId: com.x\n---\n"
            "- launchApp\n"
            "-\n    runFlow:\n      when:\n        true\n"
            "- tapOn: Continue\n"
            "-")
    # first two top-level commands: launchApp and the bare-dash runFlow block.
    out = truncate_flow(flow, 2)
    assert "launchApp" in out and "runFlow" in out
    assert "tapOn: Continue" not in out
    # the trailing bare `-` (4th command) is dropped; identity at n large enough.
    assert truncate_flow(flow, 99) == flow


def test_truncate_flow_handles_crlf_line_endings():
    from run_differential import truncate_flow
    flow = ("appId: com.x\r\n---\r\n"
            "- launchApp\r\n- tapOn: Continue\r\n- inputText: hi\r\n")
    out = truncate_flow(flow, 2)
    assert "launchApp" in out and "tapOn: Continue" in out
    assert "inputText" not in out
    # CRLF bytes are preserved verbatim in what's kept.
    assert "\r\n" in out


def test_truncate_flow_counts_crlf_bare_dash_command():
    from run_differential import truncate_flow
    # A CRLF bare-dash command (`-\r\n`) with a block child must count as a
    # top-level command, so truncating at 2 keeps it and stops BEFORE the next
    # `- tapOn`. The old detection only matched a bare-LF `-\n`, so `-\r\n`
    # slipped through uncounted and the next command leaked in.
    flow = ("appId: com.x\r\n---\r\n"
            "- launchApp\r\n"
            "-\r\n    runFlow: sub.yaml\r\n"
            "- tapOn: Continue\r\n")
    out = truncate_flow(flow, 2)
    assert "launchApp" in out and "runFlow" in out
    assert "tapOn: Continue" not in out


def test_keep_device_runs_one_side_and_skips_teardown(tmp_path, monkeypatch, capsys):
    spec = _android_spec(tmp_path)
    ex = FakeExecutor()
    monkeypatch.setattr(run_differential, "_pull_trace",
        lambda executor, dbg, local: ex.get("remote", local))
    monkeypatch.setattr(run_differential, "_pull_log", lambda e, d, l: False)
    report = run_one_folder(ex, spec, cli_2x="/2x", cli_3x="/3x",
                            out_dir=str(tmp_path/"out"), video=False,
                            device_bin="/x/fake", keep_device=True, only_side="3x")
    assert ex.booted == 1                                        # one side only
    assert sum(1 for c in ex.calls if c[0] == "teardown") == 0   # device HELD
    assert report["heldDevice"] == "emulator-1"
    scripts = [c[1] for c in ex.calls if c[0] == "sh"]
    assert any("/3x" in s and "test" in s for s in scripts)
    assert not any("/2x" in s and "test" in s for s in scripts)
    assert "HELD_DEVICE serial=emulator-1" in capsys.readouterr().out
