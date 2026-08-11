#!/usr/bin/env python3
"""run_differential.py — the Phase 2 runner: one device per folder, legacy vs device-core.

Replays a list of replay-harness run folders. For EACH folder it boots the
exact device once (local or remote via the executor seam), installs the app
once, then runs BOTH backends on that ONE device — legacy (no env var) and
device-core (MAESTRO_DEVICECORE_ASSERT=1) — with a state reset before each.
It records device-layer video (best-effort), pulls each backend's per-step
trace, diffs them through diff_traces.fidelity_report, and writes per-run
+ aggregate reports.

One fresh device per folder shared by both backends is the whole point: boot
once, teardown once, reset between backends. Booting per backend would inject
device-to-device variance into the diff.

This module reports data; it is NOT a pass/fail gate. A flow FAIL/ERROR is
data, not a harness error — the CLI always runs with check=False.

Output layout:
  out/<runId>/legacy/steps.jsonl
  out/<runId>/legacy/screen.mp4        (if --video)
  out/<runId>/devicecore/steps.jsonl
  out/<runId>/devicecore/screen.mp4    (if --video)
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
from diff_traces import fidelity_report
# Bound at module level so tests can monkeypatch run_differential.LocalExecutor
# / run_differential.run_cli.
from executor import LocalExecutor, RemoteExecutor, run_cli

BACKENDS = [("legacy", {}), ("devicecore", {"MAESTRO_DEVICECORE_ASSERT": "1"})]

_VIDEO_KEY = {"legacy": "videoLegacy", "devicecore": "videoDeviceCore"}


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


def run_one_folder(executor, spec, cli, out_dir, video, device_bin, tol=2,
                   run_timeout=900, work_base=None) -> dict:
    """Boot ONE device for `spec`, run both BACKENDS on it, write out/<runId>/...,
    return the per-folder report dict."""
    handle = executor.boot(
        {"platform": spec.platform, "device_spec": spec.device_spec}, device_bin
    )
    report = {
        "runId": spec.run_id, "platform": spec.platform, "package": spec.package_id,
        "specFidelity": handle.spec_fidelity, "status": "error",
        "reachDepth": 0, "served": 0, "agree": 0, "diverge": 0,
        "owed": 0, "infra": 0, "missing": 0, "fidelityGreen": False,
        "videoLegacy": False, "videoDeviceCore": False,
    }
    try:
        if work_base is None:
            work_base = f"/tmp/rundiff-{spec.run_id}"
        executor.sh(f"mkdir -p {shlex.quote(work_base)}")

        # Stage the app binary + the workspace (one path works local and remote).
        app_remote = f"{work_base}/{os.path.basename(spec.app_binary)}"
        executor.put(spec.app_binary, app_remote)
        flow_remote = _stage_workspace(executor, spec, work_base)

        # Install the app to the device ONCE (shared by both backends).
        if spec.platform == "IOS":
            app_bundle = device_ops.ios_extract_app(
                executor, app_remote, f"{work_base}/appextract"
            )
            install_argv = install_cmd("IOS", handle.device_id, app_bundle)
        else:
            install_argv = install_cmd("ANDROID", handle.device_id, app_remote)
        executor.sh(" ".join(shlex.quote(a) for a in install_argv))

        # Per-run env, passed VERBATIM + IDENTICALLY to both backends.
        env_args = []
        for k, v in spec.env.items():
            env_args += ["-e", f"{k}={v}"]
        env_args_str = " ".join(shlex.quote(a) for a in env_args)

        trace_paths = {}
        for backend_name, backend_env in BACKENDS:
            video_key = _VIDEO_KEY[backend_name]

            # (a) Reset app state before EACH backend (best-effort).
            executor.sh(
                reset_cmd(spec.platform, handle.device_id, spec.package_id) + " || true",
                check=False,
            )

            # (b) Video start — best-effort: a video failure must NEVER abort a run.
            token = None
            remote_vid = None
            if video:
                if spec.platform == "ANDROID":
                    remote_vid = f"/data/local/tmp/{backend_name}.mp4"
                else:
                    remote_vid = f"{work_base}/{backend_name}.mp4"
                try:
                    token = device_ops.start_video(
                        executor, spec.platform, handle.device_id, remote_vid
                    )
                except Exception:
                    token = None
                    report[video_key] = False

            # (c) Run the CLI (check=False — a flow FAIL/ERROR is data, not a harness error)
            # and pull its per-step trace, through the shared executor.run_cli helper.
            dbg = f"{work_base}/{backend_name}-out"
            local_trace = f"{out_dir}/{spec.run_id}/{backend_name}/steps.jsonl"
            pulled = run_cli(
                executor, cli=cli, device_id=handle.device_id, platform=spec.platform,
                dbg=dbg, flow_remote=flow_remote, local_trace_path=local_trace,
                env_args_str=env_args_str, backend_env=backend_env, timeout=run_timeout,
            )
            trace_paths[backend_name] = local_trace if pulled else None

            # (d) Video stop + pull — best-effort, wrapped so a hiccup can't fail the folder.
            if video:
                local_vid = f"{out_dir}/{spec.run_id}/{backend_name}/screen.mp4"
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

        # After both backends: diff via the reused fidelity framework.
        legacy_trace = trace_paths.get("legacy")
        dc_trace = trace_paths.get("devicecore")
        if (legacy_trace and dc_trace
                and os.path.exists(legacy_trace) and os.path.exists(dc_trace)):
            fr = fidelity_report(legacy_trace, dc_trace, tol, spec.run_id)
            diff_path = f"{out_dir}/{spec.run_id}/diff.json"
            os.makedirs(os.path.dirname(diff_path), exist_ok=True)
            with open(diff_path, "w") as fh:
                json.dump(fr, fh, indent=2)
            report.update({
                "status": "ok",
                "reachDepth": fr["deviceCoreSteps"],
                "served": fr["served"],
                "agree": fr["agree"],
                "diverge": fr["diverge"],
                "owed": fr["owedCoverageGaps"],
                "infra": fr["infraGaps"],
                "missing": fr["missing"],
                "fidelityGreen": fr["fidelityGreen"],
            })
        else:
            report["status"] = "incomplete"
    finally:
        executor.teardown(handle)

    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Replay run folders on legacy vs device-core; diff per-step traces."
    )
    ap.add_argument("--executor", choices=["local", "remote"], required=True)
    ap.add_argument("--host-alias", help="remote host alias (required for --executor remote)")
    ap.add_argument("--cli", required=True, help="path to the branch CLI (carries both backends)")
    ap.add_argument("--video", action="store_true", help="record device-layer video per backend")
    ap.add_argument("--device-bin", default="maestro-device",
                    help="the maestro-device wrapper used to boot the exact device")
    ap.add_argument("--out", default="out", help="output directory")
    ap.add_argument("--tol", type=int, default=2, help="coord tolerance (px) for the diff")
    ap.add_argument("--run-timeout", type=int, default=900, help="per-backend CLI timeout (s)")
    ap.add_argument("folders", nargs="+", help="run-folder paths / globs")
    args = ap.parse_args(argv)

    folders = expand_folders(args.folders)

    if args.executor == "local":
        executor = LocalExecutor()
    else:
        if not args.host_alias:
            ap.error("--host-alias is required for --executor remote")
        executor = RemoteExecutor(args.host_alias)

    reports = []
    for folder in folders:
        try:
            spec = read_run_folder(folder)
            rep = run_one_folder(
                executor, spec, cli=args.cli, out_dir=args.out, video=args.video,
                device_bin=args.device_bin, tol=args.tol, run_timeout=args.run_timeout,
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
            f"agree={rep.get('agree')} diverge={rep.get('diverge')} "
            f"owed={rep.get('owed')} infra={rep.get('infra')}"
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
