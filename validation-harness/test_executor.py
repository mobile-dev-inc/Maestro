import json, os, stat, textwrap
import executor as executor_mod
from executor import (
    parse_ready, LocalExecutor, RemoteExecutor, ExistingDeviceExecutor, run_cli,
    DEVICE_BIN_ENV, resolve_device_bin, require_device_bin_available, DeviceHandle,
)

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
    # CLI output is tee'd to a log under `dbg` (process substitution, not a
    # `| tee` pipe, so run_with_timeout's own exit status survives in $?)
    assert "mkdir -p /remote/out" in run_script
    assert "tee /remote/out/cli.log" in run_script
    # pull query
    assert "find" in sh_calls[1] and "steps.jsonl" in sh_calls[1]
    get_calls = [c for c in ex.calls if c[0] == "get"]
    assert len(get_calls) == 2
    # the cli.log pull happens before the trace pull, and always uses the
    # exact remote path build_cli_script tee'd to — no find needed for it.
    assert get_calls[0][1] == "/remote/out/cli.log"
    assert get_calls[0][2] == os.path.join(os.path.dirname(local_trace), "cli.log")
    assert os.path.exists(get_calls[0][2])
    assert get_calls[1][1] == "/remote/out/f/trace/steps.jsonl"


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


def test_run_cli_missing_log_is_best_effort(tmp_path):
    """A run that crashed before `mkdir -p dbg` even ran leaves no cli.log on
    the remote side — get() fails. That must not raise or block the trace
    pull; it's the pre-fix status quo (nothing to inspect), not a new failure
    mode."""
    class NoLogExecutor(FakeCliExecutor):
        def get(self, remote, local, timeout=None):
            self.calls.append(("get", remote, local))
            if remote.endswith("cli.log"):
                return False
            return super().get(remote, local, timeout=timeout)

    ex = NoLogExecutor()
    local_trace = str(tmp_path / "out" / "steps.jsonl")
    pulled = run_cli(
        ex, cli="/x/maestro", device_id="emulator-1", platform="ANDROID",
        dbg="/remote/out", flow_remote="/remote/ws/flow.yaml",
        local_trace_path=local_trace, backend_env={},
    )
    assert pulled is True
    assert os.path.exists(local_trace)
    assert not os.path.exists(os.path.join(tmp_path, "out", "cli.log"))


# ── Fix 3: configurable maestro-device path + fail-fast preflight ──────────
def test_resolve_device_bin_precedence(monkeypatch):
    monkeypatch.delenv(DEVICE_BIN_ENV, raising=False)
    # default: no arg, no env -> literal "maestro-device" (resolved on PATH at boot time)
    assert resolve_device_bin(None) == "maestro-device"
    # env wins over default
    monkeypatch.setenv(DEVICE_BIN_ENV, "/env/maestro-device")
    assert resolve_device_bin(None) == "/env/maestro-device"
    # explicit arg wins over env
    assert resolve_device_bin("/cli/maestro-device") == "/cli/maestro-device"


def test_require_device_bin_available_passes_for_real_executable(tmp_path):
    fake = tmp_path / "maestro-device"
    fake.write_text("#!/bin/bash\necho hi\n")
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    require_device_bin_available(str(fake))  # must not raise


def test_require_device_bin_available_fails_fast_naming_flag_and_env(tmp_path):
    import pytest
    missing = str(tmp_path / "does-not-exist-maestro-device")
    with pytest.raises(RuntimeError) as exc_info:
        require_device_bin_available(missing)
    message = str(exc_info.value)
    assert "--maestro-device" in message
    assert DEVICE_BIN_ENV in message
    assert "--device" in message  # points at the skip-boot escape hatch too


def test_local_boot_missing_device_bin_raises_clear_error_not_file_not_found_error(tmp_path):
    # Regression for finding #1: LocalExecutor.boot() used to let a missing
    # wrapper surface as a raw FileNotFoundError from inside Popen. It must
    # now fail fast with the same actionable RuntimeError, before ever
    # shelling out.
    import pytest
    ex = LocalExecutor()
    spec = {"platform": "ANDROID", "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None}}
    missing = str(tmp_path / "does-not-exist-maestro-device")
    with pytest.raises(RuntimeError) as exc_info:
        ex.boot(spec, device_bin=missing, timeout=30)
    assert "--maestro-device" in str(exc_info.value)
    assert DEVICE_BIN_ENV in str(exc_info.value)


# ── Fix 3: ExistingDeviceExecutor — skip-boot path against a live device ───
class _BoomIfBooted:
    """Stand-in inner executor: boot()/teardown() raise if ever called, since
    ExistingDeviceExecutor must never delegate them. sh/put/get record calls
    so delegation THROUGH ExistingDeviceExecutor can be asserted."""
    def __init__(self):
        self.calls = []

    def boot(self, *a, **kw):
        raise AssertionError("boot() must never be called in skip-boot mode")

    def teardown(self, *a, **kw):
        raise AssertionError("teardown() must never be called in skip-boot mode")

    def sh(self, script, timeout=None, check=True):
        self.calls.append(("sh", script))
        class R: stdout = ""; returncode = 0
        return R()

    def put(self, local, remote, timeout=None):
        self.calls.append(("put", local, remote))

    def get(self, remote, local, timeout=None):
        self.calls.append(("get", remote, local))
        return True


def test_existing_device_executor_boot_and_teardown_are_noops():
    inner = _BoomIfBooted()
    ex = ExistingDeviceExecutor(inner, "emulator-4444")
    handle = ex.boot({"platform": "ANDROID"}, device_bin="maestro-device", timeout=30)
    assert isinstance(handle, DeviceHandle)
    assert handle.device_id == "emulator-4444"
    assert handle.platform == "ANDROID"
    ex.teardown(handle)  # must not raise, must not touch inner.teardown


def test_existing_device_executor_delegates_sh_put_get():
    inner = _BoomIfBooted()
    ex = ExistingDeviceExecutor(inner, "emulator-4444")
    ex.sh("echo hi")
    ex.put("/local/a", "/remote/b")
    ex.get("/remote/c", "/local/d")
    assert inner.calls == [("sh", "echo hi"), ("put", "/local/a", "/remote/b"), ("get", "/remote/c", "/local/d")]


# ── ControlMaster/ControlPath: ssh/scp share one multiplexed connection ────
def test_remote_ssh_and_scp_share_controlmaster(monkeypatch):
    """quad mode fires 12+ sh()/put()/get() calls per flow; without
    ControlMaster each pays a fresh TCP+SSH handshake. Assert every ssh AND
    scp argv (Remote.sh / Remote.put / Remote.get, plus the __init__ $HOME
    probe which itself calls sh()) carries the multiplexing options, and that
    they all resolve to the SAME ControlPath so they share one master."""
    calls = []

    class FakeCP:
        stdout = "/home/fakeuser"
        stderr = ""
        returncode = 0

    def fake_run(cmd, **kwargs):
        calls.append(cmd)
        return FakeCP()

    monkeypatch.setattr(executor_mod.subprocess, "run", fake_run)

    r = executor_mod.Remote({"host": "1.2.3.4", "user": "u", "password": "p"})
    r.sh("echo hi")
    r.put("/local/a", "/remote/b")
    r.get("/remote/c", "/local/d")

    assert len(calls) == 4  # __init__'s $HOME probe + sh + put + get
    control_paths = set()
    for cmd in calls:
        assert "ControlMaster=auto" in cmd
        assert "ControlPersist=60s" in cmd
        cp_opts = [a for a in cmd if isinstance(a, str) and a.startswith("ControlPath=")]
        assert len(cp_opts) == 1
        control_paths.add(cp_opts[0])
    assert len(control_paths) == 1  # every call shares the same socket path
    # ssh calls (sh) use the `ssh` binary; scp calls (put/get) use `scp` —
    # both must carry ControlMaster/ControlPath, not just one of them.
    assert calls[0][2] == "ssh"    # __init__ $HOME probe
    assert calls[1][2] == "ssh"    # sh()
    assert calls[2][2] == "scp"    # put()
    assert calls[3][2] == "scp"    # get()
