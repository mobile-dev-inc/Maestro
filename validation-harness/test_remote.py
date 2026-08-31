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

def test_host_is_idle_ios():
    assert host_is_idle("IOS", "== Devices ==\n(no devices booted)\n") is True
    assert host_is_idle("IOS", "iPhone 16 Pro (ABC-123) (Booted)") is False

def test_claim_probe_uses_name_only_pgrep():
    # SF-2/NH-1: pgrep must match process NAMES only (-l), not whole cmdlines (-fl),
    # so an SDK path or JVM classpath containing 'maestro'/'qemu' can't flip busy.
    for plat in ("ANDROID", "IOS"):
        s = claim_probe_script(plat)
        assert "pgrep -l" in s
        assert "pgrep -fl" not in s

def test_host_is_idle_ignores_signal_substrings_in_paths():
    # NH-1: an unrelated line mentioning 'maestro' as a path/arg is NOT a process
    idle_probe = ("@@ADB@@\nList of devices attached\n\n"
                  "@@PROC@@\nusing sdk at /opt/tools/maestro/bin/adb\n")
    assert host_is_idle("ANDROID", idle_probe) is True
    # a real pgrep process-name line (`PID name`) whose name is qemu means busy
    busy_probe = ("@@ADB@@\nList of devices attached\n\n"
                  "@@PROC@@\n12345 qemu-system-aarch64\n")
    assert host_is_idle("ANDROID", busy_probe) is False

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

def test_ssh_run_quotes_multiword_script_as_single_word():
    # B1: a multi-word script must reach the remote shell as ONE word, else the
    # local ssh flattens it and the remote re-splits (`bash -lc mkdir` + leaked ops).
    import shlex as _sh
    r = FakeRunner()
    remote.ssh_run(CREDS, "echo HELLO WORLD", runner=r)
    argv = r.calls[0]["argv"]
    assert _sh.quote("echo HELLO WORLD") in argv       # single quoted word present
    assert "HELLO" not in argv and "WORLD" not in argv  # not separate trailing tokens

def test_poll_done_true_when_sentinel_present():
    r = FakeRunner({"test -f": "DONE-PRESENT"})
    # ssh_run returns rc 0; poll keys off the DONE-PRESENT marker the script echoes
    assert remote.poll_done(CREDS, "out/DONE", runner=r) is True

def test_poll_done_false_on_profile_noise():
    # SF-3: a login shell can echo profile noise; only the marker counts as DONE
    r = FakeRunner({"test -f": "Welcome to host\nLast login: ..."})
    assert remote.poll_done(CREDS, "out/DONE", runner=r) is False

def test_pull_out_counted_verifies_and_raises_on_mismatch(tmp_path, monkeypatch):
    # remote reports 3 files; simulate a local extract of only 2 -> raise
    r = FakeRunner({"wc -l": "3"})
    monkeypatch.setattr(remote, "_stream_tar", lambda *a, **k: None)   # SF-5: no socket
    monkeypatch.setattr(remote, "_local_file_count", lambda d: 2)
    with pytest.raises(RuntimeError):
        remote.pull_out_counted(CREDS, "~/scratch", "out", str(tmp_path), runner=r)

def test_pull_out_counted_happy_path_is_hermetic(tmp_path, monkeypatch):
    # SF-5: the streaming is a helper the test stubs out, so no real ssh/tar socket.
    r = FakeRunner({"wc -l": "4"})
    seen = {}
    def fake_stream(creds, remote_dir, subdir, local_dir):
        seen["call"] = (remote_dir, subdir, local_dir)
    monkeypatch.setattr(remote, "_stream_tar", fake_stream)
    monkeypatch.setattr(remote, "_local_file_count", lambda d: 4)
    n = remote.pull_out_counted(CREDS, "~/scratch", "out", str(tmp_path), runner=r)
    assert n == 4
    assert seen["call"][1] == "out"

def test_pull_out_counted_raises_when_find_call_fails(tmp_path, monkeypatch):
    # SF-1: a failed `find | wc -l` SSH call must not be read as 0==0 clean.
    monkeypatch.setattr(remote, "_stream_tar", lambda *a, **k: None)
    def failing(argv, **kw):
        class R: stdout = "0"; stderr = "connection lost"; returncode = 1
        return R()
    with pytest.raises(RuntimeError):
        remote.pull_out_counted(CREDS, "~/scratch", "out", str(tmp_path), runner=failing)

def test_pull_out_counted_raises_when_remote_count_zero(tmp_path, monkeypatch):
    # SF-1: an empty tree (remote_n == 0) is a failed/empty collect, not a clean run.
    monkeypatch.setattr(remote, "_stream_tar", lambda *a, **k: None)
    r = FakeRunner({"wc -l": "0"})
    with pytest.raises(RuntimeError):
        remote.pull_out_counted(CREDS, "~/scratch", "out", str(tmp_path), runner=r)

def test_local_file_count_skips_symlinks(tmp_path):
    # NH-3: os.walk counts symlinks but remote `find -type f` doesn't -> skip locally.
    (tmp_path / "real.txt").write_text("x")
    (tmp_path / "link.txt").symlink_to(tmp_path / "real.txt")
    assert remote._local_file_count(str(tmp_path)) == 1
