import json, os, stat, textwrap
from executor import parse_ready, LocalExecutor, DeviceHandle, sweep_ios_clones

def test_parse_ready_android():
    assert parse_ready("READY platform=android serial=emulator-5588 name=foo", "ANDROID") == "emulator-5588"

def test_parse_ready_ios():
    assert parse_ready("READY platform=ios udid=ABCD1234-EF56-7890-ABCD-1234567890AB name=foo", "IOS") \
        == "ABCD1234-EF56-7890-ABCD-1234567890AB"

def test_parse_ready_missing_raises():
    import pytest
    with pytest.raises(ValueError):
        parse_ready("not a ready line", "ANDROID")

def _fake_wrapper(tmp_path):
    fake = tmp_path / "fake-device"
    fake.write_text('#!/bin/bash\necho "READY platform=android serial=emulator-9999 name=fake"\nsleep 300\n')
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    return str(fake)

def test_local_boot_parses_handle(tmp_path):
    ex = LocalExecutor()
    spec = {"platform": "ANDROID", "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None}}
    handle = ex.boot(spec, device_bin=_fake_wrapper(tmp_path), timeout=30)
    try:
        assert handle.device_id == "emulator-9999"
        assert handle.platform == "ANDROID"
        assert handle.spec_fidelity == "full"
    finally:
        handle._proc.terminate()

def test_local_boot_android_locale_marks_approx(tmp_path):
    ex = LocalExecutor()
    spec = {"platform": "ANDROID", "device_spec": {"model": "pixel_6", "os": "android-34", "locale": "fr_FR"}}
    handle = ex.boot(spec, device_bin=_fake_wrapper(tmp_path), timeout=30)
    try:
        assert handle.spec_fidelity == "approx"
    finally:
        handle._proc.terminate()

def test_local_boot_dies_before_ready_raises(tmp_path):
    import pytest
    fake = tmp_path / "dies"
    fake.write_text('#!/bin/bash\necho "starting"\nexit 1\n')
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    ex = LocalExecutor()
    spec = {"platform": "ANDROID", "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None}}
    with pytest.raises(RuntimeError):
        ex.boot(spec, device_bin=str(fake), timeout=30)

def test_local_sh_and_get(tmp_path):
    ex = LocalExecutor()
    r = ex.sh("echo hello")
    assert r.returncode == 0 and "hello" in r.stdout
    src = tmp_path / "a.txt"; src.write_text("x")
    dst = tmp_path / "b.txt"
    assert ex.get(str(src), str(dst)) is True
    assert dst.read_text() == "x"

def test_local_boot_closes_parent_log_fd(tmp_path, monkeypatch):
    # Regression for fix round 1: LocalExecutor.boot() used to hand its own
    # open(logfile, "w") handle to Popen and never close it, leaking one fd
    # in the PARENT process per boot() call (run_differential.py loops boot()
    # over many folders in one process and would exhaust ulimit -n). The
    # child keeps writing via its own dup'd fd (unaffected); only the
    # parent's handle must be closed once Popen has started the child.
    #
    # This spies on the builtin open() to capture the exact file object
    # executor.py opens in write mode for the logfile, and asserts it was
    # closed by the time boot() returns. A plain "lsof after boot() returns"
    # check doesn't discriminate here: CPython's refcounting closes the
    # leaked handle as soon as boot()'s frame exits regardless of whether
    # the fix is applied, since nothing else keeps a reference to it.
    import builtins
    opened = []
    real_open = builtins.open

    def spy_open(file, mode="r", *args, **kwargs):
        f = real_open(file, mode, *args, **kwargs)
        if isinstance(file, str) and os.path.basename(file).startswith("mdev-") and "w" in mode:
            opened.append(f)
        return f

    monkeypatch.setattr(builtins, "open", spy_open)

    ex = LocalExecutor()
    spec = {"platform": "ANDROID", "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None}}
    handle = ex.boot(spec, device_bin=_fake_wrapper(tmp_path), timeout=30)
    try:
        assert len(opened) == 1, "expected exactly one write-mode open() for the logfile"
        assert opened[0].closed is True, "parent's write-mode logfile handle was not closed after boot()"
    finally:
        handle._proc.terminate()


# --- teardown: per-run device cleanup that preserves goldens ---

class _RecRunner:
    """Records every device argv; returns a canned stdout by substring match."""
    def __init__(self, stdout_by_needle=None):
        self.calls = []
        self.stdout_by_needle = stdout_by_needle or {}

    def __call__(self, argv, capture_output=True, text=False, timeout=None, **kw):
        self.calls.append(argv)
        out = ""
        joined = " ".join(argv)
        for needle, val in self.stdout_by_needle.items():
            if needle in joined:
                out = val
        class R:
            stdout = out
            returncode = 0
        return R()

    def joined(self):
        return [" ".join(c) for c in self.calls]


class _FakeProc:
    def __init__(self): self.terminated = False; self.killed = False
    def terminate(self): self.terminated = True
    def wait(self, timeout=None): return 0
    def kill(self): self.killed = True


def _sim_list(*devs):
    return json.dumps({"devices": {"com.apple.CoreSimulator.SimRuntime.iOS-18-2": list(devs)}})


def test_teardown_ios_deletes_the_per_run_clone_and_removes_logfile(tmp_path):
    logf = tmp_path / "mdev-x.log"; logf.write_text("boot log")
    r = _RecRunner({"list devices": _sim_list(
        {"udid": "R-1", "name": "RUNTIME_MAESTRO_ab12cd34", "state": "Shutdown"})})
    h = DeviceHandle("R-1", "IOS", "full", _FakeProc(), str(logf))
    LocalExecutor().teardown(h, runner=r)
    j = r.joined()
    assert any("xcrun simctl shutdown R-1" in c for c in j)
    assert any("xcrun simctl delete R-1" in c for c in j)   # the clone is deleted
    assert not logf.exists()                                # boot logfile is junk, removed


def test_teardown_ios_never_deletes_a_golden(tmp_path):
    # Defence-in-depth: even if the booted udid resolved to a GOLDEN, teardown
    # must shut it down but NEVER delete it.
    r = _RecRunner({"list devices": _sim_list(
        {"udid": "G-1", "name": "GOLDEN_MAESTRO_iPhone-15_iOS-18-2_v7", "state": "Shutdown"})})
    h = DeviceHandle("G-1", "IOS", "full", _FakeProc(), "")
    LocalExecutor().teardown(h, runner=r)
    j = r.joined()
    assert any("xcrun simctl shutdown G-1" in c for c in j)
    assert not any("simctl delete" in c for c in j)         # golden is sacred


def test_teardown_ios_skips_delete_when_wrapper_hook_already_deleted_it(tmp_path):
    # maestro-device's SIGTERM shutdown hook already deleted the clone → the udid
    # is absent from the list → no (harmless) delete is issued.
    r = _RecRunner({"list devices": _sim_list()})
    h = DeviceHandle("R-9", "IOS", "full", _FakeProc(), "")
    LocalExecutor().teardown(h, runner=r)
    assert not any("simctl delete" in c for c in r.joined())


def test_teardown_android_kills_emulator_and_deletes_no_device(tmp_path):
    r = _RecRunner()
    h = DeviceHandle("emulator-5", "ANDROID", "full", _FakeProc(), "")
    LocalExecutor().teardown(h, runner=r)
    j = r.joined()
    assert any("adb -s emulator-5 emu kill" in c for c in j)
    assert not any("simctl" in c for c in j)               # no per-run device on Android


def test_sweep_ios_clones_reclaims_only_shutdown_clones_never_golden_or_booted():
    r = _RecRunner({"list devices": _sim_list(
        {"udid": "G-1", "name": "GOLDEN_MAESTRO_iPhone-15_iOS-18-2_v7", "state": "Shutdown"},
        {"udid": "R-1", "name": "RUNTIME_MAESTRO_ab12cd34", "state": "Shutdown"},
        {"udid": "R-2", "name": "RUNTIME_MAESTRO_ff99ff99", "state": "Booted"},
        {"udid": "S-1", "name": "SCRATCH_VALIDATE_dead", "state": "Shutdown"},
        {"udid": "U-1", "name": "iPhone 16 Pro", "state": "Shutdown"})})
    deleted = sweep_ios_clones(runner=r)
    assert set(deleted) == {"R-1", "S-1"}
    j = r.joined()
    assert any("simctl delete R-1" in c for c in j)
    assert any("simctl delete S-1" in c for c in j)
    assert not any("simctl delete G-1" in c for c in j)    # golden preserved
    assert not any("simctl delete R-2" in c for c in j)    # booted (in-use) preserved
    assert not any("simctl delete U-1" in c for c in j)    # unrelated device untouched


def test_sweep_ios_clones_tolerates_a_failing_list():
    def boom(*a, **k): raise RuntimeError("simctl unavailable")
    assert sweep_ios_clones(runner=boom) == []             # best-effort, never raises
