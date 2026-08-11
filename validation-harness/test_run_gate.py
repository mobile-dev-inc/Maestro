"""Tests for run_gate.py — the corpus runner's non-transport logic.

There's no real ssh/emulator here (that's exercised manually against a real
host); these lock in: (1) the corpus-index path is never hardcoded and fails
fast without one, and (2) run_flow() drives every CLI pass through the
shared executor.run_cli helper — no private "generate one big script with
its own run_with_timeout" left in this module (Task P5).
"""
import json
import os

import pytest

from run_gate import android_worklist, run_flow, CORPUS_INDEX_ENV


def test_android_worklist_requires_corpus_index_arg_or_env(monkeypatch):
    monkeypatch.delenv(CORPUS_INDEX_ENV, raising=False)
    with pytest.raises(SystemExit):
        android_worklist()


def test_android_worklist_reads_explicit_path(tmp_path, monkeypatch):
    monkeypatch.delenv(CORPUS_INDEX_ENV, raising=False)
    run_dir = tmp_path / "run_x"
    run_dir.mkdir()
    (run_dir / "metadata.json").write_text(json.dumps({"package_id": "com.x", "env": {"K": "V"}}))
    corpus_index = tmp_path / "corpus-index.json"
    corpus_index.write_text(json.dumps({"flows": [
        {"org": "Acme", "runId": "run_x", "platform": "ANDROID", "runDir": str(run_dir),
         "flowFilePath": "flows/f.yaml", "deviceSpec": {"os": "android-34"}},
        {"org": "Acme", "runId": "run_ios", "platform": "IOS", "runDir": str(run_dir),
         "flowFilePath": "flows/f.yaml", "deviceSpec": {"os": "ios-17"}},
    ]}))

    items = android_worklist(str(corpus_index))
    assert len(items) == 1  # iOS filtered out — Android-only per this module's docstring
    assert items[0]["key"] == "Acme_run_x"
    assert items[0]["package"] == "com.x"
    assert items[0]["env"] == {"K": "V"}


def test_android_worklist_falls_back_to_env_var(tmp_path, monkeypatch):
    run_dir = tmp_path / "run_x"
    run_dir.mkdir()
    (run_dir / "metadata.json").write_text(json.dumps({"package_id": "com.x"}))
    corpus_index = tmp_path / "corpus-index.json"
    corpus_index.write_text(json.dumps({"flows": [
        {"org": "Acme", "runId": "run_x", "platform": "ANDROID", "runDir": str(run_dir),
         "flowFilePath": "flows/f.yaml", "deviceSpec": {"os": "android-34"}},
    ]}))
    monkeypatch.setenv(CORPUS_INDEX_ENV, str(corpus_index))

    items = android_worklist()
    assert len(items) == 1


class FakeRemote:
    """Duck-types the Remote protocol run_flow/run_cli need: expand/sh/put/get."""
    def __init__(self):
        self.calls = []

    def expand(self, remote):
        if remote.startswith("~/"):
            return "/home/fake/" + remote[2:]
        return remote

    def sh(self, script, timeout=None, check=True):
        self.calls.append(("sh", script))
        class R:
            stdout = "/remote/out/f/trace/steps.jsonl"
            stderr = ""
            returncode = 0
        return R()

    def put(self, local, remote, timeout=None):
        self.calls.append(("put", str(local), remote))

    def get(self, remote, local, timeout=None):
        self.calls.append(("get", remote, local))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        with open(local, "w") as fh:
            fh.write(json.dumps({"stepIndex": 0, "backendId": "x",
                                  "command": {"type": "LaunchAppCommand"}, "verdict": "PASS"}) + "\n")
        return True


def _item(tmp_path):
    run_dir = tmp_path / "run_x"
    (run_dir / "workspace" / "flows").mkdir(parents=True)
    (run_dir / "workspace" / "flows" / "f.yaml").write_text("appId: com.x\n---\n- launchApp\n")
    (run_dir / "app.apk").write_text("apk")
    return {
        "key": "Acme_run_x", "org": "Acme", "runDir": str(run_dir),
        "flowFilePath": "flows/f.yaml", "package": "com.x", "env": {"K": "V"},
        "androidOs": "android-34",
    }


def test_run_flow_drives_every_side_through_shared_run_cli(tmp_path):
    remote = FakeRemote()
    item = _item(tmp_path)
    traces_dir = tmp_path / "traces"

    report = run_flow(remote, item, "emulator-1", traces_dir,
                      run_timeout=42, scp_timeout=60, ssh_timeout=30,
                      log=lambda m: None, mode="gate")

    assert report["key"] == "Acme_run_x"
    assert report["pulled"] == {"a": True, "b": True}
    assert os.path.exists(str(traces_dir / "Acme_run_x" / "a" / "steps.jsonl"))
    assert os.path.exists(str(traces_dir / "Acme_run_x" / "b" / "steps.jsonl"))

    sh_scripts = [c[1] for c in remote.calls if c[0] == "sh"]
    # Both CLI passes go through executor.run_cli's shared script (watchdog
    # present); this module no longer builds its own multi-side script.
    run_scripts = [s for s in sh_scripts if "run_with_timeout" in s]
    assert len(run_scripts) == 2
    for s in run_scripts:
        assert "MAESTRO_STEP_TRACE=1" in s
        assert "-e K=V" in s
        assert "flows/f.yaml" in s
    # pm clear once per side (2), install once, uninstall once.
    resets = [s for s in sh_scripts if "pm clear" in s]
    assert len(resets) == 2
    installs = [s for s in sh_scripts if " install -r " in s]
    assert len(installs) == 1
    uninstalls = [s for s in sh_scripts if "uninstall" in s]
    assert len(uninstalls) == 1


def test_run_flow_control_legacy_mode_runs_same_cli_twice(tmp_path):
    remote = FakeRemote()
    item = _item(tmp_path)
    traces_dir = tmp_path / "traces"

    report = run_flow(remote, item, "emulator-1", traces_dir,
                      run_timeout=42, scp_timeout=60, ssh_timeout=30,
                      log=lambda m: None, mode="control-legacy")

    assert report["pulled"] == {"a": True, "b": True}
