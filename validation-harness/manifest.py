"""manifest.py — versioned identity of a differential batch.

Builds the ~1 KB manifest.json that answers "exactly which binaries produced
this data?" — per binary: role, repo, git SHA, dirty flag, effective
device-core version, content hash of the install tree, build time; plus the
harness SHA, host, timestamp, tolerance, and corpus source.

The 3x role carries the EFFECTIVE device-core version: the gitignored
devicecore.version.local override (written by scripts/devicecore-sync.sh) wins
over the committed devicecore.version pin. Stdlib only — no third-party deps.
"""
from __future__ import annotations
import hashlib, os, shutil, subprocess


def effective_devicecore_version(maestro_root: str) -> str:
    local = os.path.join(maestro_root, "devicecore.version.local")
    pinned = os.path.join(maestro_root, "devicecore.version")
    path = local if os.path.isfile(local) else pinned
    with open(path) as fh:
        return fh.read().strip()


def git_identity(repo_dir: str, runner=subprocess.run) -> dict:
    sha = runner(["git", "-C", repo_dir, "rev-parse", "HEAD"],
                 capture_output=True, text=True, check=False).stdout.strip()
    status = runner(["git", "-C", repo_dir, "status", "--porcelain"],
                    capture_output=True, text=True, check=False).stdout.strip()
    return {"repo": os.path.basename(os.path.normpath(repo_dir)),
            "gitSha": sha, "dirty": bool(status)}


def content_hash(tree_dir: str) -> str:
    h = hashlib.sha256()
    for root, _dirs, files in os.walk(tree_dir):
        for name in sorted(files):
            p = os.path.join(root, name)
            rel = os.path.relpath(p, tree_dir)
            h.update(rel.encode()); h.update(b"\0")
            with open(p, "rb") as fh:
                for chunk in iter(lambda: fh.read(65536), b""):
                    h.update(chunk)
    return "sha256:" + h.hexdigest()


def build_manifest(binaries, harness_sha, host, timestamp, tol, corpus_src) -> dict:
    return {
        "binaries": binaries,
        "harnessSha": harness_sha,
        "host": host,
        "timestamp": timestamp,
        "tol": tol,
        "corpusSource": corpus_src,
    }


def vendor_bins(trees: dict, dest_bin_dir: str) -> dict:
    """Copy each distinct install tree into dest_bin_dir/<contentHash>/ once.

    Opt-in vendoring: two trees with the same content hash are copied only once
    (a batch's 2.x and 3.x oracle can coincide). Returns {role: contentHash}.
    An empty `trees` writes nothing — the default is no bin/ at all."""
    mapping = {}
    for role, tree in trees.items():
        ch = content_hash(tree)
        mapping[role] = ch
        target = os.path.join(dest_bin_dir, ch.replace("sha256:", ""))
        if not os.path.isdir(target):        # dedup: copy each distinct hash once
            os.makedirs(dest_bin_dir, exist_ok=True)
            shutil.copytree(tree, target)
    return mapping
