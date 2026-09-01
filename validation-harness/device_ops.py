"""device_ops.py — platform-general device ops: install / reset / video.

Thin builders (pure functions returning argv lists / shell strings, no I/O)
plus small executor-driven wrappers so run_differential.py (a later task)
can install the app, reset state between backends, and record video at the
device layer — regardless of which backend (Maestro vs device-core) is under
test. Video is device-layer because device-core has no recording verb.

Branch on `platform`: "ANDROID" -> adb, "IOS" -> simctl.
"""
from __future__ import annotations

import json
import os
import shlex
import time


# --- iOS per-run device cleanup (see remote self-cleanup feature) ---
#
# `maestro-device launch ios` clones its GOLDEN simulator into a fresh per-run
# RUNTIME_MAESTRO_<uuid> device (`simctl clone`) and deletes it in a JVM
# shutdown hook on SIGTERM. That hook does NOT run on SIGKILL/crash, so a
# crashed run leaks the clone into ~/Library/Developer/CoreSimulator/Devices —
# the accumulation this harness cleans up. The prefixes below MIRROR
# maestro-device's own SimEntry tiers. The GOLDEN is the persistent, reusable
# base image: it is NEVER deleted. Android has no per-run device (it reuses the
# golden AVD and resets userdata in place), so there is nothing to sweep there.
_GOLDEN_SIM_PREFIX = "GOLDEN_MAESTRO_"
_SWEEPABLE_SIM_PREFIXES = ("RUNTIME_MAESTRO_", "SCRATCH_MAESTRO_", "SCRATCH_VALIDATE_")


def is_golden_sim_name(name: str) -> bool:
    """A persistent, reusable base simulator — sacred, never deleted."""
    return (name or "").startswith(_GOLDEN_SIM_PREFIX)


def is_sweepable_sim_name(name: str) -> bool:
    """A per-run clone / scratch simulator that is safe to delete — and is
    provably NOT a golden (the golden guard wins even if a prefix overlapped)."""
    name = name or ""
    return (not is_golden_sim_name(name)) and name.startswith(_SWEEPABLE_SIM_PREFIXES)


def sim_list_json_cmd() -> list:
    return ["xcrun", "simctl", "list", "devices", "-j"]


def sim_delete_cmd(udid: str) -> list:
    return ["xcrun", "simctl", "delete", udid]


def sim_name_for_udid(list_json: str, udid: str) -> str | None:
    """Return the device name for `udid` from `simctl list devices -j`, or None
    if the udid isn't present (e.g. maestro-device's hook already deleted it)."""
    data = json.loads(list_json or "{}")
    for devs in data.get("devices", {}).values():
        for d in devs:
            if d.get("udid") == udid:
                return d.get("name")
    return None


def sweepable_clone_udids(list_json: str) -> list:
    """From `simctl list devices -j`, the udids of leaked per-run clones — those
    whose NAME is sweepable AND that are NOT currently Booted. Excludes goldens
    (by name) and in-use/booted clones (a concurrent run's live device), so the
    sweep is safe on a shared, harness-claimed host."""
    data = json.loads(list_json or "{}")
    out = []
    for devs in data.get("devices", {}).values():
        for d in devs:
            if is_sweepable_sim_name(d.get("name", "")) and d.get("state") != "Booted":
                udid = d.get("udid")
                if udid:
                    out.append(udid)
    return out


def install_cmd(platform: str, device_id: str, app_remote: str) -> list:
    """Build the install argv for the given platform.

    ANDROID: app_remote is an .apk path.
    IOS: app_remote is an extracted .app bundle path (see ios_extract_app).
    """
    if platform == "ANDROID":
        return ["adb", "-s", device_id, "install", "-r", app_remote]
    if platform == "IOS":
        return ["xcrun", "simctl", "install", device_id, app_remote]
    raise ValueError(f"unknown platform: {platform!r}")


def reset_cmd(platform: str, device_id: str, package_id: str) -> str:
    """Build the between-backend reset command.

    ANDROID: `pm clear` wipes app state/storage in place.
    IOS: has no `pm clear` equivalent; `simctl terminate` is the light reset
    (stops the running process). A fuller iOS reset — uninstall + reinstall —
    is left to the runner to sequence when it needs a harder wipe; this
    builder only provides the light terminate-based reset.
    """
    if platform == "ANDROID":
        return f"adb -s {device_id} shell pm clear {package_id}"
    if platform == "IOS":
        return f"xcrun simctl terminate {device_id} {package_id}"
    raise ValueError(f"unknown platform: {platform!r}")


def video_start_cmd(platform: str, device_id: str, remote_out: str) -> str:
    """Build the (foreground) recorder command; the runner backgrounds it.

    ANDROID: `adb shell screenrecord` caps each recording at ~3 minutes per
    segment. This task records a single segment only (see start_video /
    stop_video_and_pull below) — long flows may be truncated to that first
    segment. Multi-segment chunking (looping screenrecord into numbered
    files and concatenating) is deferred to a follow-up task; it is NOT
    implemented here, and this is a real limitation, not silently ignored.
    IOS: `simctl io recordVideo` has no such cap.
    """
    if platform == "ANDROID":
        return f"adb -s {device_id} shell screenrecord {remote_out}"
    if platform == "IOS":
        return f"xcrun simctl io {device_id} recordVideo --force {remote_out}"
    raise ValueError(f"unknown platform: {platform!r}")


def ios_extract_app(executor, ipa_remote: str, workdir_remote: str) -> str:
    """Unzip an .ipa into workdir_remote and return the extracted .app path.

    Real ipas vary: most extract to Payload/*.app, but some (e.g. Airalo's
    app.ipa) extract the bundle to the archive root instead. A bounded
    recursive find (maxdepth 3) covers both layouts without assuming either.

    Raises RuntimeError if no .app is found anywhere under workdir_remote.
    """
    executor.sh(f"unzip -o {ipa_remote} -d {workdir_remote}")
    res = executor.sh(
        f"find {shlex.quote(workdir_remote)} -maxdepth 3 -name '*.app' -type d | head -1",
        check=False,
    )
    app_path = res.stdout.strip()
    if not app_path:
        raise RuntimeError(f"no .app found under {workdir_remote} after extracting {ipa_remote}")
    return app_path


def start_video(executor, platform, device_id, remote_out):
    """Launch the device-layer recorder in the background.

    Returns a token dict that stop_video_and_pull needs to stop the
    recording and locate the file. Recording runs until signalled (SIGINT)
    by stop_video_and_pull.

    ANDROID: single-segment only — see video_start_cmd's docstring for the
    ~3-minute screenrecord cap and the deferred multi-segment chunking.
    """
    cmd = video_start_cmd(platform, device_id, remote_out)
    executor.sh(f"nohup {cmd} > /dev/null 2>&1 & echo started")
    return {"remote_out": remote_out}


def stop_video_and_pull(executor, platform, device_id, token, remote_out, local_out) -> bool:
    """Stop the recorder, flush it, and pull the result to local_out.

    Video is best-effort and must never fail a run: any exception here is
    swallowed and this returns False.
    """
    try:
        if platform == "ANDROID":
            executor.sh(f"adb -s {device_id} shell pkill -INT screenrecord || true", check=False)
            time.sleep(1)
            remote_tmp = f"/tmp/{device_id}-screen.mp4"
            executor.sh(f"adb -s {device_id} pull {remote_out} {remote_tmp}", check=False)
            executor.get(remote_tmp, local_out)
            # Don't leave the pulled-through host temp behind (harness junk).
            executor.sh(f"rm -f {shlex.quote(remote_tmp)}", check=False)
        elif platform == "IOS":
            executor.sh(f"pkill -INT -f 'simctl io {device_id}' || true", check=False)
            time.sleep(1)
            executor.get(remote_out, local_out)
        else:
            raise ValueError(f"unknown platform: {platform!r}")
    except Exception:
        return False

    return os.path.exists(local_out)
