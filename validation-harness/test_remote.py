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
