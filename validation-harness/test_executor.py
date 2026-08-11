import json, os, stat, textwrap
from executor import parse_ready, LocalExecutor, run_cli

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


# ── run_cli: the single shared "run one CLI pass, pull its trace" helper ────
class FakeCliExecutor:
    """Records every sh/get call; `get` writes a canned steps.jsonl."""
    def __init__(self):
        self.calls = []

    def sh(self, script, timeout=None, check=True):
        self.calls.append(("sh", script))
        class R:
            stdout = "/remote/out/f/trace/steps.jsonl"
            returncode = 0
        return R()

    def get(self, remote, local, timeout=None):
        self.calls.append(("get", remote, local))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        with open(local, "w") as fh:
            fh.write(json.dumps({"stepIndex": 0, "backendId": "x",
                                  "command": {"type": "LaunchAppCommand"}, "verdict": "PASS"}) + "\n")
        return True

    def put(self, local, remote, timeout=None):
        self.calls.append(("put", local, remote))


def test_run_cli_generates_env_watchdog_and_pulls_trace(tmp_path):
    ex = FakeCliExecutor()
    local_trace = str(tmp_path / "out" / "steps.jsonl")
    pulled = run_cli(
        ex, cli="/x/maestro", device_id="emulator-5680", platform="ANDROID",
        dbg="/remote/out", flow_remote="/remote/ws/flow.yaml",
        local_trace_path=local_trace, env_args_str="-e K=V",
        backend_env={"MAESTRO_DEVICECORE_ASSERT": "1"}, timeout=42,
    )
    assert pulled is True
    assert os.path.exists(local_trace)

    sh_calls = [c[1] for c in ex.calls if c[0] == "sh"]
    assert len(sh_calls) == 2  # the CLI run script + the find-steps.jsonl pull query
    run_script = sh_calls[0]
    # env preamble
    assert "JAVA_HOME" in run_script and "MAESTRO_STEP_TRACE=1" in run_script
    assert "unset MAESTRO_DEVICECORE_ASSERT" in run_script
    assert "MAESTRO_DEVICECORE_ASSERT=1" in run_script
    assert "ANDROID_SERIAL=emulator-5680" in run_script
    # portable watchdog
    assert "run_with_timeout" in run_script
    assert "run_with_timeout 42 " in run_script
    # the actual CLI invocation
    assert "/x/maestro" in run_script and "test" in run_script
    assert "-e K=V" in run_script
    assert "/remote/ws/flow.yaml" in run_script
    # pull query
    assert "find" in sh_calls[1] and "steps.jsonl" in sh_calls[1]
    get_calls = [c for c in ex.calls if c[0] == "get"]
    assert len(get_calls) == 1
    assert get_calls[0][1] == "/remote/out/f/trace/steps.jsonl"


def test_run_cli_no_trace_found_returns_false(tmp_path):
    class NoTraceExecutor(FakeCliExecutor):
        def sh(self, script, timeout=None, check=True):
            self.calls.append(("sh", script))
            class R:
                stdout = ""
                returncode = 0
            return R()

    ex = NoTraceExecutor()
    local_trace = str(tmp_path / "out" / "steps.jsonl")
    pulled = run_cli(
        ex, cli="/x/maestro", device_id="emulator-1", platform="ANDROID",
        dbg="/remote/out", flow_remote="/remote/ws/flow.yaml",
        local_trace_path=local_trace, backend_env={},
    )
    assert pulled is False
    assert not os.path.exists(local_trace)
