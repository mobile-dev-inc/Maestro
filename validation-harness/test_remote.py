# validation-harness/test_remote.py
import pytest
import remote
from remote import (ssh_argv, claim_probe_script, host_is_idle,
                    remote_run_script, verify_pull_counts)

def test_ssh_argv_has_keepalives_and_target():
    argv = ssh_argv("10.0.0.11", "admin")
    assert argv[:3] == ["sshpass", "-e", "ssh"]
    assert argv[-1] == "admin@10.0.0.11"
    joined = " ".join(argv)
    assert "ServerAliveInterval=15" in joined
    assert "StrictHostKeyChecking=accept-new" in joined

def test_claim_probe_script_branches_by_platform():
    assert "adb devices" in claim_probe_script("ANDROID")
    assert "simctl list devices booted" in claim_probe_script("IOS")

def test_host_is_idle_android():
    idle = "List of devices attached\n\n"          # no serials, no procs
    busy = "List of devices attached\nemulator-5554\tdevice\n"
    assert host_is_idle("ANDROID", idle) is True
    assert host_is_idle("ANDROID", busy) is False
    assert host_is_idle("ANDROID", "12345 maestro studio") is False

def test_host_is_idle_ios():
    assert host_is_idle("IOS", "== Devices ==\n(no devices booted)\n") is True
    assert host_is_idle("IOS", "iPhone 16 Pro (ABC-123) (Booted)") is False

def test_remote_run_script_invokes_run_differential_and_touches_done():
    s = remote_run_script(
        remote_dir="~/dir-research-scratch/dcdiff",
        device_bin="art/maestro-device/bin/maestro-device",
        cli_2x="art/2x/bin/maestro", cli_3x="art/3x/bin/maestro",
        out_dir="out", folders=["corpus/run_a", "corpus/run_b"],
        done_sentinel="out/DONE", log="out/run.log",
    )
    assert "nohup" in s and "run_differential.py" in s
    assert "--device-bin" in s and "--cli-2x" in s and "--cli-3x" in s
    assert "corpus/run_a" in s and "corpus/run_b" in s
    # DONE must be touched AFTER the run, inside the detached shell
    assert "touch" in s and "DONE" in s
    assert s.rstrip().endswith("&")

def test_verify_pull_counts():
    verify_pull_counts(10, 10)  # no raise
    with pytest.raises(RuntimeError):
        verify_pull_counts(10, 7)


# --- Task 4: thin shells over a fake runner ---
from inventory import HostCreds

class FakeRunner:
    """Records every argv+env and returns scripted stdouts by match substring."""
    def __init__(self, stdouts=None):
        self.calls = []
        self.stdouts = stdouts or {}
    def __call__(self, argv, capture_output=True, text=True, env=None, timeout=None, check=False):
        self.calls.append({"argv": argv, "env": env})
        out = ""
        joined = " ".join(argv)
        for needle, val in self.stdouts.items():
            if needle in joined:
                out = val
        class R:
            stdout = out
            stderr = ""
            returncode = 0
        return R()

CREDS = HostCreds(host="arm-m2m-1", ip="10.0.0.21", user="admin", password="pw x")

def test_ssh_run_injects_sshpass_env_not_argv():
    r = FakeRunner()
    remote.ssh_run(CREDS, "echo hi", runner=r)
    call = r.calls[0]
    assert call["env"]["SSHPASS"] == "pw x"
    assert "pw x" not in " ".join(call["argv"])       # never on the command line
    assert "admin@10.0.0.21" in call["argv"]
    assert "echo hi" in " ".join(call["argv"])

def test_poll_done_true_when_sentinel_present():
    r = FakeRunner({"test -f": "FOUND"})
    # ssh_run returns rc 0; poll keys off a marker the script echoes
    assert remote.poll_done(CREDS, "out/DONE", runner=r) is True

def test_pull_out_counted_verifies_and_raises_on_mismatch(tmp_path, monkeypatch):
    # remote reports 3 files; simulate a local extract of only 2 -> raise
    r = FakeRunner({"wc -l": "3"})
    monkeypatch.setattr(remote, "_local_file_count", lambda d: 2)
    with pytest.raises(RuntimeError):
        remote.pull_out_counted(CREDS, "~/scratch", "out", str(tmp_path), runner=r)
