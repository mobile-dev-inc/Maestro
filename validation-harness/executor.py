"""executor.py — the executor seam: LocalExecutor + exact-spec boot.

One small interface (`sh`, `put`, `get`, `boot`, `teardown`) so
run_differential.py can replay a corpus locally through a single code path.
The deliverable is local-only: the remote/run_gate executor path has been
removed.

Boot reuses the existing `maestro-device` wrapper — it owns the device
lifecycle end to end (creates, boots, blocks until SIGTERM, tears down on
exit). This module never reimplements AVD/simulator creation; it only
launches the wrapper, waits for its READY line, and holds the handle needed
to tear it down later.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass

READY_RE = {
    "ANDROID": re.compile(r"serial=(emulator-\d+)"),
    "IOS": re.compile(r"udid=([A-Fa-f0-9-]+)"),
}


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
