"""provenance.py — per-run source + provenance records.

Two tiny JSON records written per run into out/<runId>/:
  - source.json     — where this run came from: corpus path, upload id, and the
                      app binary's content hash + storage path (the APK/IPA is
                      referenced by hash, never copied into results).
  - provenance.json — which manifest binaries (by content hash) drove this run.

The pairing lets a result folder resolve back to the exact corpus input and the
exact binaries, without shipping either. Stdlib only.
"""
from __future__ import annotations
import json, os
import manifest as _manifest


def write_source(run_out_dir: str, spec, upload_id=None) -> dict:
    app_hash = None
    app_storage = None
    if getattr(spec, "app_binary", None):
        app_hash = _manifest.content_hash(spec.app_binary) \
            if os.path.isdir(spec.app_binary) else _file_hash(spec.app_binary)
        app_storage = spec.app_binary
    src = {
        "corpusPath": spec.run_dir,
        "uploadId": upload_id,
        "appContentHash": app_hash,
        "appStoragePath": app_storage,
    }
    os.makedirs(run_out_dir, exist_ok=True)
    with open(os.path.join(run_out_dir, "source.json"), "w") as fh:
        json.dump(src, fh, indent=2)
    return src


def write_provenance(run_out_dir: str, manifest_binaries) -> dict:
    prov = {"binaries": [{"role": b["role"], "contentHash": b["contentHash"]}
                         for b in manifest_binaries]}
    os.makedirs(run_out_dir, exist_ok=True)
    with open(os.path.join(run_out_dir, "provenance.json"), "w") as fh:
        json.dump(prov, fh, indent=2)
    return prov


def _file_hash(path: str) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()
