#!/usr/bin/env python3
"""run_differential.py — the Phase 2 runner: two binaries, one clean device each.

Replays a list of replay-harness run folders. For EACH folder it runs the 2.x
oracle binary (`--cli-2x`) and the 3.x candidate binary (`--cli-3x`), each on
its OWN freshly-booted clean device — boot, stage/install/reset/video/run/pull,
teardown — before moving to the next side. It records device-layer video
(best-effort), pulls each side's per-step trace, diffs them through
fidelity.fidelity_report, and writes per-run + aggregate reports.

A fresh device PER SIDE is the point here: the two sides are different CLI
binaries (not an env-toggle on one binary), so there is no shared device to
reuse between them.

This module reports data; it is NOT a pass/fail gate. A flow FAIL/ERROR is
data, not a harness error — the CLI always runs with check=False.

Local execution only — the remote/run_gate executor path has been removed.

Output layout:
  out/<runId>/2x/steps.jsonl
  out/<runId>/2x/screen.mp4            (if --video)
  out/<runId>/3x/steps.jsonl
  out/<runId>/3x/screen.mp4            (if --video)
  out/<runId>/diff.json
  out/report.json                      (aggregate)
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import tarfile
import tempfile

import device_ops
from device_ops import install_cmd, reset_cmd
from run_folder import read_run_folder, expand_folders
from fidelity import fidelity_report
import viewer
# Bound at module level so tests can monkeypatch run_differential.LocalExecutor.
from executor import LocalExecutor

SIDES = ["2x", "3x"]

# JAVA_HOME + PATH prepend for both platforms. adb (android-sdk platform-tools)
# on PATH is required for device-core provisioning on Android; harmless on iOS
# and when an android-sdk path is absent. Both the pool location
# ($HOME/android-sdk) and the local Mac SDK location
# ($HOME/Library/Android/sdk) are prepended so this works on either box.
_PATH_PREAMBLE = (
    'export JAVA_HOME=/opt/homebrew/opt/openjdk@17; '
    'export PATH="$JAVA_HOME/bin:$HOME/Library/Android/sdk/platform-tools:'
    '$HOME/android-sdk/platform-tools:$PATH"'
)

_VIDEO_KEY = {"2x": "video2x", "3x": "video3x"}


def _pull_trace(executor, dbg, local_path) -> bool:
    """Find the CLI-written steps.jsonl under `dbg` and pull it to local_path.

    The CLI writes it to <dbg>/<flowname>/trace/steps.jsonl. Module-level so the
    test can monkeypatch it. Returns True iff a trace was found AND pulled.
    """
    res = executor.sh(f"find {shlex.quote(dbg)} -name steps.jsonl | head -1", check=False)
    remote_trace = (res.stdout or "").strip()
    if not remote_trace:
        return False
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    return executor.get(remote_trace, local_path)


def _stage_workspace(executor, spec, work_base) -> str:
    """Tar the local workspace, put it on the executor, untar it. Return flow_remote."""
    fd, tar_local = tempfile.mkstemp(prefix="rundiff-ws-", suffix=".tar")
    os.close(fd)
    try:
        with tarfile.open(tar_local, "w") as tar:
            tar.add(spec.workspace_dir, arcname=".")
        executor.put(tar_local, f"{work_base}/workspace.tar")
    finally:
        try:
            os.remove(tar_local)
        except OSError:
            pass
    ws = shlex.quote(f"{work_base}/workspace")
    executor.sh(
        f"mkdir -p {ws} && "
        f"tar -C {ws} -xf {shlex.quote(work_base + '/workspace.tar')}"
    )
    flow_rel = os.path.relpath(spec.flow_file, spec.workspace_dir)
    return f"{work_base}/workspace/{flow_rel}"


def _run_cli_script(cli, device_id, platform, dbg, flow_remote, env_args_str) -> str:
    """Build the one-pass CLI invocation for a side."""
    export_vars = ["MAESTRO_STEP_TRACE=1", "MAESTRO_CLI_NO_ANALYTICS=true"]
    if platform == "ANDROID":
        # device-core's AndroidDeviceProvider issues its own adb calls WITHOUT
        # -s <serial> (unlike the maestro CLI, which always passes --device).
        # With more than one emulator running those calls are ambiguous
        # ("adb: more than one device/emulator"). ANDROID_SERIAL disambiguates
        # them via standard adb behavior. Exported for BOTH sides — a no-op
        # for the 2.x oracle (which never shells out to adb directly),
        # essential for the 3.x candidate.
        export_vars.append(f"ANDROID_SERIAL={device_id}")
    exports = "export " + " ".join(export_vars)
    return (
        f"{_PATH_PREAMBLE}\n"
        f"{exports}\n"
        f"{shlex.quote(cli)} --device {shlex.quote(device_id)} test "
        f"--debug-output={shlex.quote(dbg)} --flatten-debug-output "
        f"{env_args_str} {shlex.quote(flow_remote)}"
    )


def run_one_folder(executor, spec, cli_2x, cli_3x, out_dir, video, device_bin, tol=2,
                   run_timeout=900, work_base=None) -> dict:
    """Run each SIDE for `spec` on its OWN freshly-booted clean device, write
    out/<runId>/..., return the per-folder report dict."""
    cli_for = {"2x": cli_2x, "3x": cli_3x}
    report = {
        "runId": spec.run_id, "platform": spec.platform, "package": spec.package_id,
        "specFidelity2x": None, "specFidelity3x": None, "status": "error",
        "reachDepth": 0, "served": 0, "agree": 0, "diverge": 0,
        "owed": 0, "missing": 0, "fidelityGreen": False,
        "video2x": False, "video3x": False,
    }

    # Per-run env, passed VERBATIM + IDENTICALLY to both sides.
    env_args = []
    for k, v in spec.env.items():
        env_args += ["-e", f"{k}={v}"]
    env_args_str = " ".join(shlex.quote(a) for a in env_args)

    trace_paths = {}
    for side in SIDES:
        video_key = _VIDEO_KEY[side]
        handle = executor.boot(
            {"platform": spec.platform, "device_spec": spec.device_spec}, device_bin
        )
        # specFidelity is per-side: each side boots its OWN device, and a
        # degraded boot (e.g. locale fallback) on one side must never be
        # masked by the other side's reading.
        report[f"specFidelity{side}"] = handle.spec_fidelity
        try:
            side_work_base = f"{work_base or f'/tmp/rundiff-{spec.run_id}'}-{side}"
            executor.sh(f"mkdir -p {shlex.quote(side_work_base)}")

            # Stage the app binary + the workspace on THIS side's device.
            app_remote = f"{side_work_base}/{os.path.basename(spec.app_binary)}"
            executor.put(spec.app_binary, app_remote)
            flow_remote = _stage_workspace(executor, spec, side_work_base)

            # Install the app.
            if spec.platform == "IOS":
                app_bundle = device_ops.ios_extract_app(
                    executor, app_remote, f"{side_work_base}/appextract"
                )
                install_argv = install_cmd("IOS", handle.device_id, app_bundle)
            else:
                install_argv = install_cmd("ANDROID", handle.device_id, app_remote)
            executor.sh(" ".join(shlex.quote(a) for a in install_argv))

            # Reset app state (best-effort) — a clean boot should already be
            # clean, but this keeps behavior consistent with a warm device.
            executor.sh(
                reset_cmd(spec.platform, handle.device_id, spec.package_id) + " || true",
                check=False,
            )

            # Video start — best-effort: a video failure must NEVER abort a run.
            token = None
            remote_vid = None
            if video:
                if spec.platform == "ANDROID":
                    remote_vid = f"/data/local/tmp/{side}.mp4"
                else:
                    remote_vid = f"{side_work_base}/{side}.mp4"
                try:
                    token = device_ops.start_video(
                        executor, spec.platform, handle.device_id, remote_vid
                    )
                except Exception:
                    token = None
                    report[video_key] = False

            # Run the CLI (check=False — a flow FAIL/ERROR is data, not a harness error).
            dbg = f"{side_work_base}/{side}-out"
            script = _run_cli_script(
                cli_for[side], handle.device_id, spec.platform, dbg, flow_remote, env_args_str
            )
            executor.sh(script, timeout=run_timeout, check=False)

            # Video stop + pull — best-effort, wrapped so a hiccup can't fail the folder.
            if video:
                local_vid = f"{out_dir}/{spec.run_id}/{side}/screen.mp4"
                os.makedirs(os.path.dirname(local_vid), exist_ok=True)
                ok = False
                if token is not None:
                    try:
                        ok = device_ops.stop_video_and_pull(
                            executor, spec.platform, handle.device_id, token,
                            remote_vid, local_vid,
                        )
                    except Exception:
                        ok = False
                report[video_key] = bool(ok)

            # Pull the per-step trace.
            local_trace = f"{out_dir}/{spec.run_id}/{side}/steps.jsonl"
            os.makedirs(os.path.dirname(local_trace), exist_ok=True)
            pulled = _pull_trace(executor, dbg, local_trace)
            trace_paths[side] = local_trace if pulled else None
        finally:
            executor.teardown(handle)

    # After both sides: diff via the reused fidelity framework.
    twox_trace = trace_paths.get("2x")
    threex_trace = trace_paths.get("3x")
    if (twox_trace and threex_trace
            and os.path.exists(twox_trace) and os.path.exists(threex_trace)):
        fr = fidelity_report(twox_trace, threex_trace, tol, spec.run_id)
        run_out_dir = f"{out_dir}/{spec.run_id}"
        diff_path = f"{run_out_dir}/diff.json"
        os.makedirs(os.path.dirname(diff_path), exist_ok=True)
        with open(diff_path, "w") as fh:
            json.dump(fr, fh, indent=2)
        # The human-facing side of the harness — diff.json is the byproduct,
        # this is the thing a person actually opens.
        viewer.write_flow_report(fr, run_out_dir)
        report.update({
            "status": "ok",
            "reachDepth": fr["reachDepth"],
            "served": fr["served"],
            "agree": fr["agree"],
            "diverge": fr["diverge"],
            "owed": fr["owed"],
            "missing": fr["missing"],
            "fidelityGreen": fr["fidelityGreen"],
        })
    else:
        report["status"] = "incomplete"

    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Replay run folders on the 2.x oracle vs the 3.x candidate; diff per-step traces."
    )
    ap.add_argument("--executor", choices=["local"], default="local",
                    help="only local execution is supported (remote/run_gate path removed)")
    ap.add_argument("--cli-2x", required=True, help="path to the 2.x oracle CLI binary")
    ap.add_argument("--cli-3x", required=True, help="path to the 3.x candidate CLI binary")
    ap.add_argument("--video", action="store_true", help="record device-layer video per side")
    ap.add_argument("--device-bin", default="maestro-device",
                    help="the maestro-device wrapper used to boot the exact device")
    ap.add_argument("--out", default="out", help="output directory")
    ap.add_argument("--tol", type=int, default=2, help="coord tolerance (px) for the diff")
    ap.add_argument("--run-timeout", type=int, default=900, help="per-side CLI timeout (s)")
    ap.add_argument("folders", nargs="+", help="run-folder paths / globs")
    args = ap.parse_args(argv)

    folders = expand_folders(args.folders)

    executor = LocalExecutor()

    reports = []
    for folder in folders:
        try:
            spec = read_run_folder(folder)
            rep = run_one_folder(
                executor, spec, cli_2x=args.cli_2x, cli_3x=args.cli_3x, out_dir=args.out,
                video=args.video, device_bin=args.device_bin, tol=args.tol,
                run_timeout=args.run_timeout,
            )
        except Exception as e:
            rep = {
                "runId": os.path.basename(os.path.normpath(folder)),
                "status": "error", "error": str(e),
            }
        reports.append(rep)
        print(
            f"[rundiff] {rep.get('runId')} status={rep.get('status')} "
            f"reach={rep.get('reachDepth')} served={rep.get('served')} "
            f"agree={rep.get('agree')} diverge={rep.get('diverge')} owed={rep.get('owed')}"
        )

    os.makedirs(args.out, exist_ok=True)
    aggregate = {
        "folders": reports,
        "totalFolders": len(reports),
        "ok": sum(1 for r in reports if r.get("status") == "ok"),
        "incomplete": sum(1 for r in reports if r.get("status") == "incomplete"),
        "errors": sum(1 for r in reports if r.get("status") == "error"),
    }
    with open(os.path.join(args.out, "report.json"), "w") as fh:
        json.dump(aggregate, fh, indent=2)

    # Batch index — one row per folder, linking to that folder's own viewer.
    # runId doubles as both the flow name and the output subdirectory here
    # (see run_one_folder: out/<runId>/...).
    flow_rows = [{**r, "flow": r.get("runId"), "dir": r.get("runId")} for r in reports]
    viewer.write_runs_index(flow_rows, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
