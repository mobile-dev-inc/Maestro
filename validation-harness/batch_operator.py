"""batch_operator.py — the pool/partition operator leaf.

The swappable concerns that depend on THIS research pool: which hosts exist,
how the corpus splits across them, the smoke go/no-go selection, and the
host-idle claim gate. batch_differential's transport trunk (build/dispatch/
collect) ships-and-runs the local unit and stays unaware of the pool shape; it
imports this leaf for the operator concerns. Swapping pools means swapping this
module, not the transport. Stdlib only.
"""
from __future__ import annotations

import json
import os

from run_folder import expand_folders
import inventory as inventory_mod
import partition as partition_mod


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
