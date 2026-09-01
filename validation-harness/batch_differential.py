"""batch_differential.py — orchestrate run_differential.py across the research
pool. Local build → partition → dispatch (detached) → collect (verified pull).
The harness itself stays local-only and unaware of remoting. Stdlib only.

Subcommands: build | partition | dispatch | collect.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import shutil
import socket
import subprocess
import types

import classification
import manifest as manifest_mod
import inventory as inventory_mod
import remote
# Pool/partition concerns live in the operator leaf; re-exported here so the CLI
# wiring, dispatch_host's claim gate, and existing imports/tests are unchanged.
from batch_operator import cmd_partition, smoke_selection, claim_host

# The full TRANSITIVE local-import closure of run_differential.py + the viewer
# templates dir. Kept in sync by test_batch_differential's closure guard: any
# local module reachable from run_differential.py (directly or transitively, e.g.
# provenance -> manifest) must ship here or the remote run dies with
# ModuleNotFoundError.
HARNESS_MODULES = [
    "run_differential.py", "run_folder.py", "executor.py", "device_ops.py",
    "fidelity.py", "diff_traces.py", "viewer.py",
    "classification.py", "flow_copy.py", "provenance.py", "manifest.py",
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
    # bare `python3` on the hosts is macOS 3.9; the harness needs >=3.10, which
    # is installed at the brew path. See remote_run_script's python_bin.
    "remote_python": "/opt/homebrew/bin/python3",
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
    _write_versioned_manifest(args, art, runner=runner)
    if getattr(args, "vendor_bins", False):
        # Opt-in: copy each distinct install tree once into <work_dir>/bin/,
        # deduped by content hash. Default writes no bin/ at all.
        trees = {
            "2x": _tree_of(art["cli_2x"], "2x"),
            "3x": _tree_of(art["cli_3x"], "3x"),
            "device": _tree_of(art["device_bin"], "maestro-device"),
        }
        manifest_mod.vendor_bins(trees, os.path.join(args.work_dir, "bin"))
    return art


def _write_versioned_manifest(args, art, runner=subprocess.run):
    """Write the ~1 KB manifest.json alongside build-manifest.json.

    Per binary: role, repo/gitSha/dirty (from git_identity), content hash of the
    install tree, build time; the 3x role additionally carries the EFFECTIVE
    device-core version (devicecore.version.local override wins over the
    committed devicecore.version), read from the maestro root (cli_3x_dir)."""
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    # role -> (source repo dir, install-tree dir)
    roles = {
        "2x": (args.cli_2x_dir, _tree_of(art["cli_2x"], "2x")),
        "3x": (args.cli_3x_dir, _tree_of(art["cli_3x"], "3x")),
        "device": (args.device_dir, _tree_of(art["device_bin"], "maestro-device")),
    }
    binaries = []
    for role, (repo_dir, tree) in roles.items():
        ident = manifest_mod.git_identity(repo_dir, runner=runner)
        binaries.append({
            "role": role,
            "repo": ident["repo"],
            "gitSha": ident["gitSha"],
            "dirty": ident["dirty"],
            "deviceCoreVersion": (
                manifest_mod.effective_devicecore_version(args.cli_3x_dir)
                if role == "3x" else None
            ),
            "contentHash": manifest_mod.content_hash(tree),
            "buildTime": now,
        })
    here = os.path.dirname(os.path.abspath(__file__))
    m = manifest_mod.build_manifest(
        binaries=binaries,
        harness_sha=manifest_mod.git_identity(here, runner=runner)["gitSha"],
        host=socket.gethostname(),
        timestamp=now,
        tol=getattr(args, "tol", None),
        corpus_src=getattr(args, "corpus_src", None),
    )
    with open(os.path.join(args.work_dir, "manifest.json"), "w") as fh:
        json.dump(m, fh, indent=2)
    return m


def _tree_of(bin_path, appname):
    # .../build/install/maestro-device/bin/maestro-device -> .../build/install/maestro-device
    return os.path.dirname(os.path.dirname(bin_path))


def _renamed_tree(bin_path, alias):
    # the CLI trees are both named "maestro"; dispatch pushes them under art/<alias>.
    # Return a (src_tree, alias) marker the pusher renames to art/<alias> on the host.
    return {"src": os.path.dirname(os.path.dirname(bin_path)), "alias": alias}


def dispatch_host(host, entry, creds, artifacts, remote_root, transport,
                  remote_python="/opt/homebrew/bin/python3"):
    platform = entry["platform"]
    remote_dir = f"{remote_root}/{host}"
    if not claim_host(creds, platform, transport):
        return {"host": host, "status": "skipped-busy", "remote_dir": remote_dir}

    here = os.path.dirname(os.path.abspath(__file__))
    # 3a: clear the per-batch out/ before running. The remote scratch out/ can
    # retain run_* dirs (and a stale DONE sentinel) from a prior batch; collect
    # flattens whatever it finds under out/, so leftovers would contaminate this
    # batch's results. Wiping only out/ (not art/ or corpus/, which hold the inputs
    # this dispatch just pushed) guarantees collect sees this run's dirs alone.
    transport.ssh_run(creds, f"rm -rf {remote_dir}/out")
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
        # NH-2: a mid-run death can leave the staging path art/maestro behind, so
        # the next `scp -r` would nest it as art/maestro/maestro. Clear the staging
        # path (idempotent) before each CLI scp, on top of the art/<alias> rm below.
        transport.ssh_run(creds, f"rm -rf {remote_dir}/art/{base}")
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
    # the host's folder slice. SF-4: namespace each folder by its index so two
    # folders that share a basename (e.g. run_1) don't overwrite each other. The
    # original basename is PRESERVED inside corpus/<i>/ so run_differential's runId
    # (derived from the basename) is unchanged.
    for i, folder in enumerate(entry["folders"]):
        transport.ssh_run(creds, f"mkdir -p {remote_dir}/corpus/{i}")
        transport.scp_put(creds, folder, f"{remote_dir}/corpus/{i}/")

    script = transport.remote_run_script(
        remote_dir=remote_dir,
        device_bin="art/maestro-device/bin/maestro-device",
        cli_2x="art/2x/bin/maestro", cli_3x="art/3x/bin/maestro",
        out_dir="out",
        folders=[f"corpus/{i}/{os.path.basename(f)}" for i, f in enumerate(entry["folders"])],
        done_sentinel="out/DONE", log="out/run.log",
        python_bin=remote_python,
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
        hosts_state.append(dispatch_host(host, entry, creds, art_trees, args.remote_root, transport,
                                         remote_python=args.remote_python))

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


def merge_reports(reports):
    folders = []
    for r in reports:
        folders.extend(r.get("folders", []))
    return {
        "folders": folders,
        "totalFolders": len(folders),
        "ok": sum(1 for f in folders if f.get("status") == "ok"),
        "incomplete": sum(1 for f in folders if f.get("status") == "incomplete"),
        "errors": sum(1 for f in folders if f.get("status") == "error"),
    }


def diverging_folders(aggregate):
    return [f.get("runId") for f in aggregate.get("folders", []) if (f.get("diverge") or 0) > 0]


def cmd_collect(args, transport=remote):
    with open(os.path.join(args.work_dir, "dispatch-state.json")) as fh:
        state = json.load(fh)
    with open(args.inventory) as fh:
        inv_text = fh.read()

    # Flatten every host's per-run dirs into one shared <work_dir>/out/ so the
    # batch path produces the SAME flat out/<runId>/diff.json layout the
    # single-run path emits — the shape write_classification (and the identical-
    # semantics invariant) assumes. runIds are corpus-unique (partitioned), so a
    # move never collides.
    flat_out = os.path.join(args.work_dir, "out")
    os.makedirs(flat_out, exist_ok=True)

    reports = []
    for h in state["hosts"]:
        if h["status"] != "running":
            continue
        creds = inventory_mod.parse_host_creds(inv_text, h["host"])
        local_dir = os.path.join(args.work_dir, h["host"])
        transport.pull_out_counted(creds, h["remote_dir"], "out", local_dir)
        host_out = os.path.join(local_dir, "out")
        report_path = os.path.join(host_out, "report.json")
        if os.path.isfile(report_path):
            with open(report_path) as rf:
                reports.append(json.load(rf))
        if os.path.isdir(host_out):
            for name in sorted(os.listdir(host_out)):
                src = os.path.join(host_out, name)
                if not os.path.isdir(src):
                    continue  # skip report.json and other host-level files
                dst = os.path.join(flat_out, name)
                if os.path.exists(dst):
                    # runIds are corpus-unique after partitioning, so a collision
                    # here means two hosts produced the same runId — a partition
                    # bug. Don't let one run silently vanish under the other; warn
                    # loudly before overwriting.
                    print(f"[batch] WARNING: duplicate runId {name!r} across hosts "
                          f"(host {h['host']}); overwriting {dst} — this signals a "
                          f"partition bug, the earlier run is being discarded")
                    shutil.rmtree(dst)
                shutil.move(src, dst)

    agg = merge_reports(reports)
    with open(os.path.join(args.work_dir, "corpus-report.json"), "w") as fh:
        json.dump(agg, fh, indent=2)
    triage = diverging_folders(agg)
    with open(os.path.join(args.work_dir, "triage-folders.txt"), "w") as fh:
        fh.write("\n".join(triage) + ("\n" if triage else ""))
    classification.write_classification(
        flat_out, agg, os.path.join(args.work_dir, "classification.json")
    )
    print(f"[batch] collected {agg['totalFolders']} folders; "
          f"{len(triage)} diverging -> triage the genuine-fidelity bucket in "
          f"classification.json via triage-batch")
    return agg


def cmd_poll(args, transport=remote):
    # Check each running host's DONE sentinel so the go/no-go gate doesn't need an
    # inline snippet. Returns host -> bool (done) and prints DONE/WAITING per host.
    with open(os.path.join(args.work_dir, "dispatch-state.json")) as fh:
        state = json.load(fh)
    with open(args.inventory) as fh:
        inv_text = fh.read()

    results = {}
    for h in state["hosts"]:
        if h["status"] != "running":
            continue
        creds = inventory_mod.parse_host_creds(inv_text, h["host"])
        done_path = f"{h['remote_dir']}/out/DONE"
        done = transport.poll_done(creds, done_path)
        results[h["host"]] = bool(done)
        # A finished run's sentinel carries its exit status (3b): a nonzero code is
        # a crash-at-startup, not a clean finish. Surface it here so the go/no-go
        # gate doesn't read a crashed batch as success. done_status is optional on
        # the transport (older fakes may not implement it), so guard the lookup.
        label = "WAITING"
        if done:
            status = None
            ds = getattr(transport, "done_status", None)
            if ds is not None:
                status = ds(creds, done_path)
            label = "DONE" if status in (None, 0) else f"DONE (CRASHED exit {status})"
        print(f"{h['host']}: {label}")
    return results


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work-dir", default=DEFAULTS["work_dir"], dest="work_dir")
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="local gradle for the three artifacts")
    b.add_argument("--device-dir", default=DEFAULTS["device_dir"], dest="device_dir")
    b.add_argument("--cli-2x-dir", default=DEFAULTS["cli_2x_dir"], dest="cli_2x_dir")
    b.add_argument("--cli-3x-dir", default=DEFAULTS["cli_3x_dir"], dest="cli_3x_dir")
    b.add_argument("--vendor-bins", action="store_true", dest="vendor_bins",
                   help="copy each distinct install tree once into <work_dir>/bin/ "
                        "(deduped by content hash); default writes no bin/")
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
    d.add_argument("--remote-python", default=DEFAULTS["remote_python"], dest="remote_python",
                   help="interpreter for the detached remote run (host needs python3 >=3.10)")
    d.add_argument("--smoke", action="store_true",
                   help="one iOS + one Android host, one folder each, then STOP (go/no-go)")
    d.set_defaults(func=cmd_dispatch)

    c = sub.add_parser("collect", help="verified tar-pull + merge + triage list")
    c.add_argument("--inventory", default=DEFAULTS["inventory"], dest="inventory")
    c.set_defaults(func=cmd_collect)

    pl = sub.add_parser("poll", help="check DONE sentinels on running hosts")
    pl.add_argument("--inventory", default=DEFAULTS["inventory"], dest="inventory")
    pl.set_defaults(func=cmd_poll)

    args = ap.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
