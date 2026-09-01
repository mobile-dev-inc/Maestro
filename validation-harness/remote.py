"""remote.py — the SSH transport, copy-adapted from the device-hosts
run_remote.sh (NOT imported — that skill's copy-and-adapt rule). Split into
pure builders (this section) that are unit-tested, and thin sshpass shells
(Task 4) around an injectable runner. Stdlib only.
"""
from __future__ import annotations

import re
import shlex
import subprocess

_SSH_OPTS = [
    "-o", "StrictHostKeyChecking=accept-new",
    "-o", "ConnectTimeout=15",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=8",
    "-o", "TCPKeepAlive=yes",
]


def _remote_path(p: str) -> str:
    # shlex.quote single-quotes the WHOLE string including a leading `~`, so the
    # REMOTE shell then treats `'~/...'` as a literal `~` directory and never
    # expands it — `tar -C '~/...'` / `find '~/...'` / `cd '~/...'` / `test -f
    # '~/...'` all fail. Keep a leading `~/` bare (for remote expansion) and quote
    # only the rest; a bare `~` stays bare; anything else is fully quoted as before.
    if p == "~":
        return "~"
    if p.startswith("~/"):
        return "~/" + shlex.quote(p[2:])
    return shlex.quote(p)


def ssh_argv(ip: str, user: str) -> list[str]:
    return ["sshpass", "-e", "ssh", *_SSH_OPTS, f"{user}@{ip}"]


def scp_argv() -> list[str]:
    return ["sshpass", "-e", "scp", "-o", "StrictHostKeyChecking=accept-new"]


def claim_probe_script(platform: str) -> str:
    # -l (not -fl): match process NAMES only. `-f` matches whole command lines, so
    # an SDK path or JVM classpath that merely contains qemu/emulator/maestro would
    # falsely flip an idle host to busy (SF-2/NH-1).
    if platform == "ANDROID":
        return (
            "echo '@@ADB@@'; adb devices; "
            "echo '@@PROC@@'; pgrep -l 'qemu-system|emulator|maestro' || true"
        )
    if platform == "IOS":
        return (
            "echo '@@SIM@@'; xcrun simctl list devices booted; "
            "echo '@@PROC@@'; pgrep -l 'maestro|CoreSimulator' || true"
        )
    raise ValueError(f"unknown platform: {platform!r}")


# process-name signals for the pgrep SECONDARY guard. pgrep -l emits `PID name`
# lines, so we only ever match against the name field, never the whole probe text.
_PROC_SIGNALS = ("qemu-system", "emulator", "maestro")


def _pgrep_name_busy(text: str) -> bool:
    for line in text.splitlines():
        m = re.match(r"^\s*\d+\s+(\S.*?)\s*$", line)
        if not m:
            continue  # not a `PID name` line — a path/arg/heading, not a process
        name = m.group(1)
        if any(sig in name for sig in _PROC_SIGNALS):
            return True
    return False


def host_is_idle(platform: str, probe_output: str) -> bool:
    text = probe_output
    # SECONDARY guard: a genuine `PID <procname>` line whose name is a device runner.
    if _pgrep_name_busy(text):
        return False
    if platform == "ANDROID":
        # PRIMARY: any `emulator-NNNN\t...device` line means a device is attached.
        for line in text.splitlines():
            s = line.strip()
            parts = s.split()
            if s.startswith("emulator-") and parts and parts[-1] == "device":
                return False
        return True
    if platform == "IOS":
        # PRIMARY: a `(Booted)` line in the simctl section means a sim is up.
        return "(Booted)" not in text
    raise ValueError(f"unknown platform: {platform!r}")


# The detached run is a `nohup bash -c '...'` — it does NOT source the operator's
# login profile, so it starts with a bare env: no JAVA_HOME, no Android SDK on PATH.
# executor.boot() then launches the maestro-device wrapper with a bare
# subprocess.Popen (no env=), so the wrapper inherits that bare env — a real smoke
# run died there with "Unable to locate a Java Runtime". Export the login-shell env
# at the START of the inner command so the WHOLE process tree (the python process
# AND every subprocess it spawns) inherits it. Mirrors run_differential.py's
# _PATH_PREAMBLE (JAVA_HOME + platform-tools on PATH) and adds the Android SDK root
# so an AVD bake can find the SDK.
_REMOTE_ENV_PREAMBLE = (
    'export JAVA_HOME=/opt/homebrew/opt/openjdk@17; '
    # ANDROID_HOME differs by host: the shared pool uses $HOME/android-sdk, local
    # Macs use $HOME/Library/Android/sdk. A real smoke run on a pool host found the
    # Library path absent (run_differential.py's _PATH_PREAMBLE confirms the pool
    # uses $HOME/android-sdk). Auto-detect at run time, PREFERRING the pool dir and
    # falling back to the Library dir, so the export is robust to both.
    'export ANDROID_HOME="$( [ -d "$HOME/android-sdk" ] && echo "$HOME/android-sdk" '
    '|| echo "$HOME/Library/Android/sdk" )"; '
    'export ANDROID_SDK_ROOT="$ANDROID_HOME"; '
    # PATH derives the SDK dirs from $ANDROID_HOME, so fixing ANDROID_HOME fixes the
    # emulator/platform-tools entries too. cmdline-tools/latest/bin added defensively
    # (harmless if absent).
    'export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:'
    '$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:'
    '$HOME/android-sdk/platform-tools:$PATH"; '
)


def remote_run_script(remote_dir, device_bin, cli_2x, cli_3x, out_dir,
                      folders, done_sentinel, log,
                      python_bin="/opt/homebrew/bin/python3") -> str:
    q = shlex.quote
    folder_args = " ".join(q(f) for f in folders)
    run = (
        f"{q(python_bin)} run_differential.py --executor local "
        f"--device-bin {q(device_bin)} --cli-2x {q(cli_2x)} --cli-3x {q(cli_3x)} "
        f"--out {q(out_dir)} {folder_args}"
    )
    # Detached: export the env, run, then capture the run's exit status ($?) and
    # write it into the sentinel. The sentinel still appears on EVERY exit so the
    # poller can tell the run finished — but it now CARRIES the status so a
    # crash-at-startup (e.g. ModuleNotFoundError, exit nonzero, before any flow ran)
    # is distinguishable from a clean finish (exit 0). A flow FAIL/ERROR is data,
    # not an error, so run_differential exits 0 in that case; only a real harness
    # crash yields a nonzero code here.
    inner = (
        f"{_REMOTE_ENV_PREAMBLE}{run} > {q(log)} 2>&1; "
        f"rc=$?; echo \"$rc\" > {q(done_sentinel)}"
    )
    return f"cd {_remote_path(remote_dir)} && nohup bash -c {q(inner)} > /dev/null 2>&1 &"


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
    # B1: shlex.quote the whole script into ONE word. The local ssh flattens its
    # trailing args into a single line the remote shell re-splits; without quoting,
    # a multi-word `script` (e.g. `mkdir -p a b c`) reaches the remote as
    # `bash -lc mkdir` and the operands leak.
    argv = [*ssh_argv(creds.ip, creds.user), "bash", "-lc", shlex.quote(script)]
    return runner(argv, capture_output=True, text=True,
                  env=_env_with_sshpass(creds), timeout=timeout, check=False)


def _push_dir_tar(creds, local, remote_parent):
    # Symmetric to _stream_tar, but PUSHING. scp -r dereferences symlinks and aborts
    # on a dead one — the corpus run folders carry a dead `port/node_modules ->
    # <deleted>` link in 54/59 folders, which kills the whole push. tar's default is
    # to archive a symlink as an inert link (no follow), so a dead link is harmless.
    # Lands the tree at <remote_parent>/<basename(local)> — the SAME final location
    # `scp -r local remote_parent/` produced.
    norm = os.path.normpath(local)
    parent = os.path.dirname(norm)
    base = os.path.basename(norm)
    rq = _remote_path(remote_parent)
    local_tar = ["tar", "-C", parent, "-cf", "-", base]
    ssh = [*ssh_argv(creds.ip, creds.user), f"tar -C {rq} -xf -"]
    p1 = subprocess.Popen(local_tar, stdout=subprocess.PIPE)
    p2 = subprocess.Popen(ssh, stdin=p1.stdout, env=_env_with_sshpass(creds))
    p1.stdout.close()
    p2.communicate()
    rc_tar = p1.wait()
    rc_ssh = p2.returncode
    # A broken leg would truncate silently; fail hard instead (mirror _stream_tar).
    if rc_tar != 0:
        raise RuntimeError(f"push tar-stream local archive exited {rc_tar} for {local}")
    if rc_ssh != 0:
        raise RuntimeError(f"push tar-stream ssh extract exited {rc_ssh} into {remote_parent}")


def scp_put(creds, local, remote_path, runner=subprocess.run):
    # A DIRECTORY goes via a symlink-safe tar stream (scp -r dereferences symlinks
    # and aborts on a dead one); remote_path is the target PARENT, so the tree lands
    # at remote_path/<basename(local)> — identical to `scp -r local remote_path/`.
    if os.path.isdir(local):
        _push_dir_tar(creds, local, remote_path)
        return
    # A single FILE has no symlink hazard — keep it on plain scp.
    argv = [*scp_argv(), local, f"{creds.user}@{creds.ip}:{remote_path}"]
    cp = runner(argv, capture_output=True, text=True,
                env=_env_with_sshpass(creds), check=False)
    if cp.returncode != 0:
        raise RuntimeError(f"scp_put failed ({local} -> {remote_path}): {cp.stderr}")


def poll_done(creds, done_path, runner=subprocess.run):
    # Detached run touches DONE on exit. `test -f ... && echo DONE-PRESENT || true`
    # emits the marker only when the sentinel exists. SF-3: key off the MARKER, not
    # any non-empty stdout — a login shell (`bash -lc`, -l sources profile) can echo
    # banner/profile noise that would otherwise read as a false DONE.
    cp = ssh_run(creds, f"test -f {_remote_path(done_path)} && echo DONE-PRESENT || true", runner=runner)
    return "DONE-PRESENT" in (cp.stdout or "")


def done_status(creds, done_path, runner=subprocess.run):
    # 3b: read the run's exit status out of the sentinel (written by
    # remote_run_script as `echo "$rc" > DONE`). Returns the int exit code, or None
    # when the sentinel is absent/empty/non-numeric — i.e. the run hasn't finished
    # or wrote nothing. None is deliberately NOT 0, so a missing sentinel never
    # reads as a clean exit. A nonzero return means the run crashed (e.g. a
    # ModuleNotFoundError at import), not that a flow diverged.
    cp = ssh_run(creds, f"cat {_remote_path(done_path)} 2>/dev/null || true", runner=runner)
    tok = (cp.stdout or "").strip()
    if not tok:
        return None
    tok = tok.split()[0]
    try:
        return int(tok)
    except ValueError:
        return None


def _local_file_count(local_dir):
    # NH-3: skip symlinks so the local count matches remote `find -type f`, which
    # does not follow/count them — otherwise counts spuriously diverge.
    n = 0
    for _root, _dirs, files in os.walk(local_dir):
        for f in files:
            if not os.path.islink(os.path.join(_root, f)):
                n += 1
    return n


def _stream_tar(creds, remote_dir, subdir, local_dir):
    # SF-5: the actual socket-opening tar stream, isolated so unit tests stub it.
    rq = _remote_path(remote_dir)
    sq = shlex.quote(subdir)
    os.makedirs(local_dir, exist_ok=True)
    ssh = [*ssh_argv(creds.ip, creds.user), f"tar -C {rq} -cf - {sq}"]
    local_tar = ["tar", "-C", local_dir, "-xf", "-"]
    p1 = subprocess.Popen(ssh, stdout=subprocess.PIPE, env=_env_with_sshpass(creds))
    p2 = subprocess.Popen(local_tar, stdin=p1.stdout)
    p1.stdout.close()
    p2.communicate()
    rc_ssh = p1.wait()
    rc_tar = p2.returncode
    # SF-1: a broken ssh or tar leg would truncate silently; fail hard instead.
    if rc_ssh != 0:
        raise RuntimeError(f"tar-stream ssh leg exited {rc_ssh} pulling {remote_dir}/{subdir}")
    if rc_tar != 0:
        raise RuntimeError(f"tar-stream local extract exited {rc_tar} into {local_dir}")


def pull_out_counted(creds, remote_dir, subdir, local_dir, runner=subprocess.run):
    rq = _remote_path(remote_dir)
    sq = shlex.quote(subdir)
    # 1) remote file count — a failed `find | wc -l` SSH call must not read as 0.
    cp = ssh_run(creds, f"find {rq}/{sq} -type f | wc -l", runner=runner)
    if cp.returncode != 0:
        raise RuntimeError(
            f"remote find failed (rc={cp.returncode}) for {remote_dir}/{subdir}: {cp.stderr}"
        )
    remote_n = int((cp.stdout or "0").strip() or "0")
    # SF-1: a real collect always has output; remote_n == 0 is a failed/empty pull,
    # not a clean no-divergence run — don't let verify_pull_counts(0, 0) pass it.
    if remote_n == 0:
        raise RuntimeError(
            f"remote find counted 0 files under {remote_dir}/{subdir} — failed or empty collect"
        )
    # 2) stream the tar over ssh into a local extract (isolated for hermetic tests)
    _stream_tar(creds, remote_dir, subdir, local_dir)
    # 3) local recount + verify
    local_n = _local_file_count(os.path.join(local_dir, subdir))
    verify_pull_counts(remote_n, local_n)
    return local_n
