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
import remote

# The full import closure of run_differential.py + the viewer templates dir.
HARNESS_MODULES = [
    "run_differential.py", "run_folder.py", "executor.py", "device_ops.py",
    "fidelity.py", "diff_traces.py", "viewer.py",
]
HARNESS_DIRS = ["viewer"]

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


def smoke_selection(manifest):
    def _pick(platform):
        for host, entry in manifest.items():
            if host.startswith("_"):
                continue
            if entry["platform"] == platform and entry["folders"]:
                return host, {"platform": platform, "folders": entry["folders"][:1]}
        raise RuntimeError(f"smoke needs one {platform} host with at least one folder")
    ios_host, ios_entry = _pick("IOS")
    andr_host, andr_entry = _pick("ANDROID")
    return {ios_host: ios_entry, andr_host: andr_entry}


def claim_host(creds, platform, transport):
    probe = transport.claim_probe_script(platform)
    cp = transport.ssh_run(creds, probe)
    return transport.host_is_idle(platform, cp.stdout or "")


def _tree_of(bin_path, appname):
    # .../build/install/maestro-device/bin/maestro-device -> .../build/install/maestro-device
    return os.path.dirname(os.path.dirname(bin_path))


def _renamed_tree(bin_path, alias):
    # the CLI trees are both named "maestro"; dispatch pushes them under art/<alias>.
    # Return a (src_tree, alias) marker the pusher renames to art/<alias> on the host.
    return {"src": os.path.dirname(os.path.dirname(bin_path)), "alias": alias}


def dispatch_host(host, entry, creds, artifacts, remote_root, transport):
    platform = entry["platform"]
    remote_dir = f"{remote_root}/{host}"
    if not claim_host(creds, platform, transport):
        return {"host": host, "status": "skipped-busy", "remote_dir": remote_dir}

    here = os.path.dirname(os.path.abspath(__file__))
    transport.ssh_run(creds, f"mkdir -p {remote_dir}/art {remote_dir}/corpus {remote_dir}/out")
    # maestro-device keeps its own basename under art/ -> art/maestro-device
    transport.scp_put(creds, artifacts["device_bin_tree"], f"{remote_dir}/art/")
    # Both CLI installDist trees are named "maestro" and collide under art/.
    # Push each, then `mv` it to its alias so they land at art/2x and art/3x —
    # matching the --cli-2x art/2x/bin/maestro / --cli-3x art/3x/bin/maestro the
    # run script invokes. The mv-after-scp is the concrete de-collision.
    for key in ("cli_2x_tree", "cli_3x_tree"):
        tree = artifacts[key]
        src, alias = tree["src"], tree["alias"]
        base = os.path.basename(src)  # "maestro"
        transport.scp_put(creds, src, f"{remote_dir}/art/")
        transport.ssh_run(
            creds,
            f"rm -rf {remote_dir}/art/{alias} && mv {remote_dir}/art/{base} {remote_dir}/art/{alias}",
        )
    # harness modules + viewer templates
    for m in HARNESS_MODULES:
        transport.scp_put(creds, os.path.join(here, m), f"{remote_dir}/")
    for d in HARNESS_DIRS:
        transport.scp_put(creds, os.path.join(here, d), f"{remote_dir}/")
    # the host's folder slice
    for folder in entry["folders"]:
        transport.scp_put(creds, folder, f"{remote_dir}/corpus/")

    script = transport.remote_run_script(
        remote_dir=remote_dir,
        device_bin="art/maestro-device/bin/maestro-device",
        cli_2x="art/2x/bin/maestro", cli_3x="art/3x/bin/maestro",
        out_dir="out", folders=[f"corpus/{os.path.basename(f)}" for f in entry["folders"]],
        done_sentinel="out/DONE", log="out/run.log",
    )
    transport.ssh_run(creds, script)
    return {"host": host, "status": "running", "remote_dir": remote_dir}


def cmd_dispatch(args, transport=remote):
    with open(os.path.join(args.work_dir, "build-manifest.json")) as fh:
        art = json.load(fh)
    # resolve the installDist TREES to push (parent dirs of the bin/ launchers)
    art_trees = {
        "device_bin_tree": _tree_of(art["device_bin"], "maestro-device"),
        "cli_2x_tree": _renamed_tree(art["cli_2x"], "2x"),
        "cli_3x_tree": _renamed_tree(art["cli_3x"], "3x"),
    }
    with open(os.path.join(args.work_dir, "partition.json")) as fh:
        manifest = json.load(fh)
    with open(args.inventory) as fh:
        inv_text = fh.read()

    selection = smoke_selection(manifest) if getattr(args, "smoke", False) else \
        {h: e for h, e in manifest.items() if not h.startswith("_")}

    hosts_state = []
    for host, entry in selection.items():
        creds = inventory_mod.parse_host_creds(inv_text, host)
        hosts_state.append(dispatch_host(host, entry, creds, art_trees, args.remote_root, transport))

    state = {"smoke": bool(getattr(args, "smoke", False)), "hosts": hosts_state,
             "remote_root": args.remote_root}
    with open(os.path.join(args.work_dir, "dispatch-state.json"), "w") as fh:
        json.dump(state, fh, indent=2)
    if getattr(args, "smoke", False):
        print("[batch] SMOKE dispatched to:", ", ".join(
            f"{h['host']}={h['status']}" for h in hosts_state))
        print("[batch] Poll DONE, then `collect`, inspect the two diffs, and only "
              "then run full dispatch. This is the go/no-go gate.")
    return state


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

    d = sub.add_parser("dispatch", help="push + detached run per host (+ --smoke gate)")
    d.add_argument("--inventory", default=DEFAULTS["inventory"], dest="inventory")
    d.add_argument("--remote-root", default=DEFAULTS["remote_root"], dest="remote_root")
    d.add_argument("--smoke", action="store_true",
                   help="one iOS + one Android host, one folder each, then STOP (go/no-go)")
    d.set_defaults(func=cmd_dispatch)

    args = ap.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
