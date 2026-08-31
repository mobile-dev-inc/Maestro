"""remote.py — the SSH transport, copy-adapted from the device-hosts
run_remote.sh (NOT imported — that skill's copy-and-adapt rule). Split into
pure builders (this section) that are unit-tested, and thin sshpass shells
(Task 4) around an injectable runner. Stdlib only.
"""
from __future__ import annotations

import shlex
import subprocess

_SSH_OPTS = [
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "ConnectTimeout=15",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=8",
    "-o", "TCPKeepAlive=yes",
]


def ssh_argv(ip: str, user: str) -> list[str]:
    return ["sshpass", "-e", "ssh", *_SSH_OPTS, f"{user}@{ip}"]


def scp_argv() -> list[str]:
    return ["sshpass", "-e", "scp", "-o", "StrictHostKeyChecking=accept-new"]


def claim_probe_script(platform: str) -> str:
    if platform == "ANDROID":
        return (
            "echo '@@ADB@@'; adb devices; "
            "echo '@@PROC@@'; pgrep -fl 'qemu-system|emulator|maestro' || true"
        )
    if platform == "IOS":
        return (
            "echo '@@SIM@@'; xcrun simctl list devices booted; "
            "echo '@@PROC@@'; pgrep -fl 'maestro|CoreSimulator.*bootstatus' || true"
        )
    raise ValueError(f"unknown platform: {platform!r}")


def host_is_idle(platform: str, probe_output: str) -> bool:
    text = probe_output
    if "maestro" in text or "qemu" in text.lower():
        return False
    if platform == "ANDROID":
        # any `emulator-NNNN\tdevice` line means a device is attached
        for line in text.splitlines():
            if line.strip().startswith("emulator-") and "device" in line:
                return False
        if "emulator" in text and "pgrep" not in text:
            # a bare `emulator` process name from pgrep
            for line in text.splitlines():
                if line.strip().endswith("emulator"):
                    return False
        return True
    if platform == "IOS":
        return "(Booted)" not in text and "Booted" not in text
    raise ValueError(f"unknown platform: {platform!r}")


def remote_run_script(remote_dir, device_bin, cli_2x, cli_3x, out_dir,
                      folders, done_sentinel, log) -> str:
    q = shlex.quote
    folder_args = " ".join(q(f) for f in folders)
    run = (
        f"python3 run_differential.py --executor local "
        f"--device-bin {q(device_bin)} --cli-2x {q(cli_2x)} --cli-3x {q(cli_3x)} "
        f"--out {q(out_dir)} {folder_args}"
    )
    # Detached: run, then unconditionally touch DONE so the poller can tell the
    # run finished (success or failure — a flow FAIL/ERROR is data, not an error).
    inner = f"{run} > {q(log)} 2>&1; touch {q(done_sentinel)}"
    return f"cd {q(remote_dir)} && nohup bash -c {q(inner)} > /dev/null 2>&1 &"


def verify_pull_counts(remote_n: int, local_n: int) -> None:
    if remote_n != local_n:
        raise RuntimeError(
            f"truncated pull — remote {remote_n} files, local {local_n}. "
            f"scp -r over sshpass silently truncates; re-pull the tar stream."
        )
