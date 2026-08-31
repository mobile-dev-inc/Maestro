"""batch_differential.py — orchestrate run_differential.py across the research
pool. Local build → partition → dispatch (detached) → collect (verified pull).
The harness itself stays local-only and unaware of remoting. Stdlib only.

Subcommands: build | partition | dispatch | collect.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import types

from run_folder import expand_folders
import inventory as inventory_mod
import partition as partition_mod

HOME = os.path.expanduser("~")
DEFAULTS = {
    "device_dir": os.path.join(HOME, "codes/copilot/maestro-device"),
    "cli_2x_dir": os.path.join(HOME, "codes/worktrees/Maestro/maestro-2x-oracle"),
    "cli_3x_dir": os.path.join(HOME, "codes/worktrees/Maestro/devicecore-integration"),
    "inventory": os.path.join(HOME, "codes/copilot/didb/infrastructure/macstadium/inventory/testing.yml"),
    "work_dir": "batch-out",
    "remote_root": "~/dir-research-scratch/devicecore-differential",
}

_DEVICE_BIN_REL = "build/install/maestro-device/bin/maestro-device"
_CLI_REL = "maestro-cli/build/install/maestro/bin/maestro"


def _ns(**kw):
    """argparse.Namespace helper for tests and internal defaults."""
    base = dict(DEFAULTS)
    base.update(kw)
    return types.SimpleNamespace(**base)


def gradle_build(project_dir, tasks, runner=subprocess.run):
    argv = ["./gradlew", *tasks]
    cp = runner(argv, cwd=project_dir, capture_output=True, text=True, check=False)
    if cp.returncode != 0:
        raise RuntimeError(f"gradle {tasks} failed in {project_dir}:\n{cp.stdout}\n{cp.stderr}")


def resolve_artifacts(device_dir, cli_2x_dir, cli_3x_dir):
    art = {
        "device_bin": os.path.abspath(os.path.join(device_dir, _DEVICE_BIN_REL)),
        "cli_2x": os.path.abspath(os.path.join(cli_2x_dir, _CLI_REL)),
        "cli_3x": os.path.abspath(os.path.join(cli_3x_dir, _CLI_REL)),
    }
    for name, path in art.items():
        if not os.path.isfile(path):
            raise FileNotFoundError(f"{name} not built: {path}")
    return art


def cmd_build(args, runner=subprocess.run):
    gradle_build(args.device_dir, ["installDist"], runner=runner)
    gradle_build(args.cli_2x_dir, [":maestro-cli:installDist"], runner=runner)
    gradle_build(args.cli_3x_dir, [":maestro-cli:installDist"], runner=runner)
    art = resolve_artifacts(args.device_dir, args.cli_2x_dir, args.cli_3x_dir)
    os.makedirs(args.work_dir, exist_ok=True)
    with open(os.path.join(args.work_dir, "build-manifest.json"), "w") as fh:
        json.dump(art, fh, indent=2)
    return art


def _split_hosts(csv):
    return [h.strip() for h in (csv or "").split(",") if h.strip()]


def cmd_partition(args):
    ios_hosts = _split_hosts(args.ios_hosts)
    android_hosts = _split_hosts(args.android_hosts)
    with open(args.inventory) as fh:
        inv_text = fh.read()
    inventory_mod.validate_named_hosts(inv_text, ios_hosts, android_hosts)

    folders = expand_folders(args.folders)
    classified = []
    skipped = []
    for folder in folders:
        try:
            classified.append((folder, partition_mod.folder_platform(folder)))
        except Exception as e:
            skipped.append({"folder": folder, "reason": str(e)})

    split = partition_mod.partition(classified, ios_hosts, android_hosts)
    ios_set = set(ios_hosts)
    manifest = {}
    for host, host_folders in split.items():
        platform = "IOS" if host in ios_set else "ANDROID"
        manifest[host] = {"platform": platform, "folders": host_folders}
    if skipped:
        manifest["_skipped"] = skipped

    os.makedirs(args.work_dir, exist_ok=True)
    with open(os.path.join(args.work_dir, "partition.json"), "w") as fh:
        json.dump(manifest, fh, indent=2)
    return manifest


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work-dir", default=DEFAULTS["work_dir"], dest="work_dir")
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="local gradle for the three artifacts")
    b.add_argument("--device-dir", default=DEFAULTS["device_dir"], dest="device_dir")
    b.add_argument("--cli-2x-dir", default=DEFAULTS["cli_2x_dir"], dest="cli_2x_dir")
    b.add_argument("--cli-3x-dir", default=DEFAULTS["cli_3x_dir"], dest="cli_3x_dir")
    b.set_defaults(func=cmd_build)

    p = sub.add_parser("partition", help="corpus -> per-host folder lists")
    p.add_argument("--ios-hosts", default="", dest="ios_hosts", help="comma-separated iOS hostnames")
    p.add_argument("--android-hosts", default="", dest="android_hosts", help="comma-separated Android hostnames")
    p.add_argument("--inventory", default=DEFAULTS["inventory"], dest="inventory")
    p.add_argument("folders", nargs="+", help="corpus run-folder globs")
    p.set_defaults(func=cmd_partition)

    args = ap.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
