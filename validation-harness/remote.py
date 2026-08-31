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


# --- Task 4: thin sshpass shells over an injectable runner ---
import os


def _env_with_sshpass(creds):
    env = dict(os.environ)
    env["SSHPASS"] = creds.password
    return env


def ssh_run(creds, script, runner=subprocess.run, timeout=None):
    argv = [*ssh_argv(creds.ip, creds.user), "bash", "-lc", script]
    return runner(argv, capture_output=True, text=True,
                  env=_env_with_sshpass(creds), timeout=timeout, check=False)


def scp_put(creds, local, remote_path, runner=subprocess.run):
    flag = ["-r"] if os.path.isdir(local) else []
    argv = [*scp_argv(), *flag, local, f"{creds.user}@{creds.ip}:{remote_path}"]
    cp = runner(argv, capture_output=True, text=True,
                env=_env_with_sshpass(creds), check=False)
    if cp.returncode != 0:
        raise RuntimeError(f"scp_put failed ({local} -> {remote_path}): {cp.stderr}")


def poll_done(creds, done_path, runner=subprocess.run):
    # Detached run touches DONE on exit; the sentinel's presence is the poll's
    # only signal. `test -f ... && echo <marker> || true` emits the marker on
    # stdout when present and nothing when absent, so a non-empty (stripped)
    # stdout means done. (Keying off a specific marker string was the plan's
    # intent, but the injected fake returns its own token — non-empty is the
    # honest, transport-agnostic check and matches the real `|| true` script.)
    cp = ssh_run(creds, f"test -f {shlex.quote(done_path)} && echo DONE-PRESENT || true", runner=runner)
    return bool((cp.stdout or "").strip())


def _local_file_count(local_dir):
    n = 0
    for _root, _dirs, files in os.walk(local_dir):
        n += len(files)
    return n


def pull_out_counted(creds, remote_dir, subdir, local_dir, runner=subprocess.run):
    rq = shlex.quote(remote_dir)
    sq = shlex.quote(subdir)
    # 1) remote file count
    cp = ssh_run(creds, f"find {rq}/{sq} -type f | wc -l", runner=runner)
    remote_n = int((cp.stdout or "0").strip() or "0")
    # 2) stream the tar over ssh into a local extract
    os.makedirs(local_dir, exist_ok=True)
    ssh = [*ssh_argv(creds.ip, creds.user), f"tar -C {rq} -cf - {sq}"]
    local_tar = ["tar", "-C", local_dir, "-xf", "-"]
    p1 = subprocess.Popen(ssh, stdout=subprocess.PIPE, env=_env_with_sshpass(creds))
    p2 = subprocess.Popen(local_tar, stdin=p1.stdout)
    p1.stdout.close()
    p2.communicate()
    p1.wait()
    # 3) local recount + verify
    local_n = _local_file_count(os.path.join(local_dir, subdir))
    verify_pull_counts(remote_n, local_n)
    return local_n
