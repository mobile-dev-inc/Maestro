"""executor.py — the executor seam: LocalExecutor / RemoteExecutor + exact-spec boot.

One small interface (`sh`, `put`, `get`, `boot`, `teardown`) with two
implementations so run_differential.py (a later task) can replay a corpus
locally or against a remote macstadium host through the same code path.

Boot reuses the existing `maestro-device` wrapper — it owns the device
lifecycle end to end (creates, boots, blocks until SIGTERM, tears down on
exit). This module never reimplements AVD/simulator creation; it only
launches the wrapper, waits for its READY line, and holds the handle needed
to tear it down later.
"""
from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass

READY_RE = {
    "ANDROID": re.compile(r"serial=(emulator-\d+)"),
    "IOS": re.compile(r"udid=([A-Fa-f0-9-]+)"),
}

# ── remote host inventory + ssh/scp transport ───────────────────────────────
# Moved in from run_gate.py: RemoteExecutor is the seam's remote implementation,
# so the sshpass/scp transport it wraps belongs here, not in the script the
# seam was built to supersede.
#
# No inventory path is hardcoded — every operator's fleet inventory lives
# somewhere different. Pass one explicitly (load_host's `inventory_path`,
# RemoteExecutor's `inventory_path`, or --inventory on the CLIs that take
# it), or set this env var. Fails fast if neither is supplied.
INVENTORY_ENV = "MAESTRO_HARNESS_INVENTORY"

SSH_OPTS = [
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    "-o", "ConnectTimeout=20",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=6",
    "-o", "LogLevel=ERROR",
]


def load_host(alias, inventory_path=None):
    import yaml
    path = inventory_path or os.environ.get(INVENTORY_ENV)
    if not path:
        raise SystemExit(
            f"host inventory path required: pass --inventory, or set {INVENTORY_ENV}"
        )
    inv = yaml.safe_load(open(path))

    def find(d):
        if isinstance(d, dict):
            if alias in d and isinstance(d[alias], dict) and "ansible_host" in d[alias]:
                return d[alias]
            for v in d.values():
                r = find(v)
                if r:
                    return r
        return None

    node = find(inv)
    if not node:
        raise SystemExit(f"host alias {alias!r} not found in {path}")
    return {
        "host": node["ansible_host"],
        "user": node.get("ansible_user", "administrator"),
        "password": node["ansible_password"],
    }


class Remote:
    """ssh/scp primitives (sshpass, literal -o flags)."""

    def __init__(self, host):
        self.host = host
        self.target = f"{host['user']}@{host['host']}"
        self.env = dict(os.environ, SSHPASS=host["password"])
        # scp uses SFTP mode and does NOT expand a leading ~ like the login
        # shell does — resolve $HOME once and make scp endpoints absolute.
        self.home = self.sh("printf %s \"$HOME\"", timeout=60).stdout.strip()

    def expand(self, remote):
        if remote == "~":
            return self.home
        if remote.startswith("~/"):
            return self.home + remote[1:]
        return remote

    def sh(self, script, timeout=None, check=True):
        """Run a bash script on the host. Returns CompletedProcess."""
        cmd = ["sshpass", "-e", "ssh", *SSH_OPTS, self.target, "bash -s"]
        cp = subprocess.run(
            cmd, input=script, env=self.env, text=True,
            capture_output=True, timeout=timeout,
        )
        if check and cp.returncode != 0:
            raise RuntimeError(
                f"remote sh failed (rc={cp.returncode})\n--stdout--\n{cp.stdout}\n--stderr--\n{cp.stderr}"
            )
        return cp

    def put(self, local, remote, timeout=None):
        remote = self.expand(remote)
        cmd = ["sshpass", "-e", "scp", *SSH_OPTS, str(local), f"{self.target}:{remote}"]
        cp = subprocess.run(cmd, env=self.env, text=True, capture_output=True, timeout=timeout)
        if cp.returncode != 0:
            raise RuntimeError(f"scp put failed (rc={cp.returncode}): {cp.stderr}")

    def get(self, remote, local, timeout=None):
        remote = self.expand(remote)
        cmd = ["sshpass", "-e", "scp", *SSH_OPTS, f"{self.target}:{remote}", str(local)]
        cp = subprocess.run(cmd, env=self.env, text=True, capture_output=True, timeout=timeout)
        return cp.returncode == 0


@dataclass
class DeviceHandle:
    device_id: str      # emulator-XXXX (Android) | UDID (iOS)
    platform: str       # "ANDROID" | "IOS"
    spec_fidelity: str  # "full" | "approx"
    _proc: object        # opaque teardown handle (local Popen | remote pid string)
    _logfile: str


def parse_ready(line: str, platform: str) -> str:
    """Extract the device id (serial for ANDROID, udid for IOS) from a READY line."""
    pattern = READY_RE[platform]
    m = pattern.search(line)
    if not m:
        raise ValueError(f"no READY device id found for platform={platform!r} in line: {line!r}")
    return m.group(1)


def _build_boot_args(spec, device_bin):
    platform = spec["platform"]
    device_spec = spec["device_spec"]
    model = device_spec["model"]
    locale = device_spec.get("locale")

    if platform == "ANDROID":
        args = [device_bin, "launch", "android", "--os", device_spec["os"], "--model", model]
        spec_fidelity = "approx" if locale else "full"
    elif platform == "IOS":
        args = [device_bin, "launch", "ios", "--os", device_spec["os"], "--model", model]
        if locale:
            args += ["--locale", locale]
        spec_fidelity = "full"
    else:
        raise ValueError(f"unknown platform: {platform!r}")

    return args, spec_fidelity


class LocalExecutor:
    """Runs everything on localhost. put/get are plain file copies."""

    def sh(self, script, timeout=None, check=True):
        cp = subprocess.run(
            ["bash", "-c", script], capture_output=True, text=True, timeout=timeout,
        )
        if check and cp.returncode != 0:
            raise RuntimeError(
                f"local sh failed (rc={cp.returncode})\n--stdout--\n{cp.stdout}\n--stderr--\n{cp.stderr}"
            )
        return cp

    def put(self, local, remote, timeout=None):
        shutil.copy(local, remote)

    def get(self, remote, local, timeout=None) -> bool:
        try:
            shutil.copy(remote, local)
            return True
        except Exception:
            return False

    def boot(self, spec, device_bin, timeout=360) -> DeviceHandle:
        platform = spec["platform"]
        args, spec_fidelity = _build_boot_args(spec, device_bin)

        fd, logfile = tempfile.mkstemp(prefix="mdev-", suffix=".log")
        os.close(fd)
        log_fh = open(logfile, "w")
        try:
            proc = subprocess.Popen(args, stdout=log_fh, stderr=subprocess.STDOUT)
        finally:
            # Popen dup's the fd into the child; the parent's copy must be closed
            # here or it leaks on every boot() (run_differential.py loops this in
            # one process and will exhaust ulimit -n). The poll loop below reads
            # the log back by PATH, so closing this handle doesn't affect polling.
            log_fh.close()

        poll_interval = 0.1
        deadline = time.time() + timeout
        device_id = None
        while time.time() < deadline:
            if proc.poll() is not None:
                tail = ""
                try:
                    with open(logfile) as f:
                        tail = f.read()
                except OSError:
                    pass
                raise RuntimeError(
                    f"device wrapper exited before READY (rc={proc.returncode})\n--log--\n{tail}"
                )
            try:
                with open(logfile) as f:
                    text = f.read()
            except OSError:
                text = ""
            for line in text.splitlines():
                if "READY platform=" in line:
                    device_id = parse_ready(line, platform)
                    break
            if device_id:
                break
            time.sleep(poll_interval)

        if not device_id:
            try:
                proc.terminate()
            except Exception:
                pass
            raise TimeoutError(f"timed out waiting for READY from {device_bin} after {timeout}s")

        return DeviceHandle(device_id, platform, spec_fidelity, _proc=proc, _logfile=logfile)

    def teardown(self, handle: DeviceHandle) -> None:
        try:
            handle._proc.terminate()
        except Exception:
            pass
        try:
            handle._proc.wait(timeout=10)
        except Exception:
            try:
                handle._proc.kill()
            except Exception:
                pass
            try:
                handle._proc.wait(timeout=5)
            except Exception:
                pass

        if handle.platform == "ANDROID":
            try:
                subprocess.run(
                    ["adb", "-s", handle.device_id, "emu", "kill"],
                    capture_output=True, timeout=30,
                )
            except Exception:
                pass
        elif handle.platform == "IOS":
            try:
                subprocess.run(
                    ["xcrun", "simctl", "shutdown", handle.device_id],
                    capture_output=True, timeout=30,
                )
            except Exception:
                pass


_remote_logfile_counter = 0


def _next_remote_logfile():
    global _remote_logfile_counter
    _remote_logfile_counter += 1
    return f"/tmp/mdev-{os.getpid()}-{_remote_logfile_counter}.log"


class RemoteExecutor:
    """Wraps the sshpass-backed Remote transport (above) for the executor seam."""

    def __init__(self, host_alias: str, inventory_path: str | None = None):
        self._remote = Remote(load_host(host_alias, inventory_path=inventory_path))

    def sh(self, script, timeout=None, check=True):
        return self._remote.sh(script, timeout=timeout, check=check)

    def put(self, local, remote, timeout=None):
        return self._remote.put(local, remote, timeout=timeout)

    def get(self, remote, local, timeout=None) -> bool:
        return self._remote.get(remote, local, timeout=timeout)

    def boot(self, spec, device_bin, timeout=360) -> DeviceHandle:
        platform = spec["platform"]
        args, spec_fidelity = _build_boot_args(spec, device_bin)
        logfile = _next_remote_logfile()

        res = self._remote.sh(f"nohup {shlex.join(args)} > {logfile} 2>&1 & echo $!")
        pid = res.stdout.strip()

        poll_interval = 1.0
        deadline = time.time() + timeout
        device_id = None
        while time.time() < deadline:
            res = self._remote.sh(f"grep -m1 'READY platform=' {logfile} || true", check=False)
            line = res.stdout.strip()
            if line:
                device_id = parse_ready(line, platform)
                break
            time.sleep(poll_interval)

        if not device_id:
            self._remote.sh(f"kill {pid} || true", check=False)
            raise TimeoutError(f"timed out waiting for READY from {device_bin} on remote after {timeout}s")

        return DeviceHandle(device_id, platform, spec_fidelity, _proc=pid, _logfile=logfile)

    def teardown(self, handle: DeviceHandle) -> None:
        self._remote.sh(f"kill {handle._proc} || true", check=False)

        if handle.platform == "ANDROID":
            self._remote.sh(f"adb -s {handle.device_id} emu kill || true", check=False)
        elif handle.platform == "IOS":
            self._remote.sh(f"xcrun simctl shutdown {handle.device_id} || true", check=False)


# ── shared "run one CLI pass, pull its trace" helper ────────────────────────
# Previously hand-written 3x (run_gate.py's build_remote_script/run_side,
# phase5_fidelity.py's build_remote_script/run_side, run_differential.py's
# _run_cli_script + _pull_trace): a bash preamble exporting JAVA_HOME/PATH/
# MAESTRO_STEP_TRACE=1, a portable run_with_timeout watchdog (macOS ships no
# `timeout`/`gtimeout`), then a `find … steps.jsonl` pull. One version here,
# driven through the executor seam's sh/put/get so it works for Local and
# Remote alike.
PATH_PREAMBLE = (
    'export JAVA_HOME=/opt/homebrew/opt/openjdk@17; '
    'export PATH="$JAVA_HOME/bin:$HOME/Library/Android/sdk/platform-tools:'
    '$HOME/android-sdk/platform-tools:$PATH"'
)

_RUN_WITH_TIMEOUT = """\
run_with_timeout() {
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill -TERM "$pid" 2>/dev/null; sleep 5; kill -KILL "$pid" 2>/dev/null ) 2>/dev/null &
  local watcher=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null
  return $rc
}
"""


def build_cli_script(cli, device_id, platform, dbg, flow_remote, env_args_str, backend_env, timeout) -> str:
    """Build the one-pass CLI invocation script: env preamble + watchdog + run.

    Always unsets the device-core assert var FIRST, then re-exports it only
    for backends that ask for it via `backend_env` — `bash -c`/`bash -s`
    inherits the harness process env, so an operator with
    MAESTRO_DEVICECORE_ASSERT=1 in their own shell would otherwise run a
    "legacy" pass with device-core silently ON.
    """
    export_vars = ["MAESTRO_STEP_TRACE=1", "MAESTRO_CLI_NO_ANALYTICS=true"]
    if platform == "ANDROID":
        # device-core's AndroidDeviceProvider issues its own adb calls WITHOUT
        # -s <serial> (unlike the maestro CLI, which always passes --device).
        # With more than one emulator running those calls are ambiguous.
        # ANDROID_SERIAL disambiguates via standard adb behavior. Exported
        # for every backend — a no-op for legacy, essential for device-core.
        export_vars.append(f"ANDROID_SERIAL={device_id}")
    for k, v in (backend_env or {}).items():
        export_vars.append(f"{k}={v}")
    exports = "unset MAESTRO_DEVICECORE_ASSERT; export " + " ".join(export_vars)
    cli_cmd = (
        f"{shlex.quote(cli)} --device {shlex.quote(device_id)} test "
        f"--debug-output={shlex.quote(dbg)} --flatten-debug-output "
        f"{env_args_str} {shlex.quote(flow_remote)}"
    )
    return (
        f"{PATH_PREAMBLE}\n"
        f"{exports}\n"
        f"{_RUN_WITH_TIMEOUT}"
        f"run_with_timeout {timeout} {cli_cmd}\n"
    )


def pull_trace(executor, dbg, local_path) -> bool:
    """Find the CLI-written steps.jsonl under `dbg` and pull it to local_path.

    The CLI writes it to <dbg>/<flowname>/trace/steps.jsonl. Returns True iff
    a trace was found AND pulled.
    """
    res = executor.sh(f"find {shlex.quote(dbg)} -name steps.jsonl | head -1", check=False)
    remote_trace = (res.stdout or "").strip()
    if not remote_trace:
        return False
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    return executor.get(remote_trace, local_path)


def run_cli(executor, *, cli, device_id, platform, dbg, flow_remote, local_trace_path,
            env_args_str="", backend_env=None, timeout=900, check=False) -> bool:
    """Run one Maestro CLI pass through `executor.sh`, then pull its steps.jsonl
    trace to `local_trace_path`. Returns True iff a trace was found and pulled.

    The single seam every runner (run_differential.py; run_gate.py's
    control/quad modes) drives a CLI pass through — see the module docstring
    above for why this replaced 3 hand-written copies.
    """
    script = build_cli_script(
        cli, device_id, platform, dbg, flow_remote, env_args_str, backend_env, timeout
    )
    executor.sh(script, timeout=timeout + 30, check=check)
    return pull_trace(executor, dbg, local_trace_path)
