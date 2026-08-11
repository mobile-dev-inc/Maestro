"""Read a replay-harness run folder directly into a RunSpec.

No dependency on any corpus-index.json — metadata.json is the single source
of truth for a run folder.
"""

import glob
import json
import os
from dataclasses import dataclass
from typing import Optional


@dataclass
class RunSpec:
    run_id: str
    run_dir: str
    platform: str          # "ANDROID" | "IOS"
    package_id: str
    device_spec: dict      # {model, os, locale}
    env: dict
    workspace_dir: str     # <run_dir>/workspace
    app_binary: Optional[str]  # <run_dir>/app.apk (ANDROID) | <run_dir>/app.ipa (IOS);
                                # None when the folder has no app binary — a
                                # built-in-app flow (e.g. com.android.settings /
                                # com.apple.Preferences) that installs nothing.
    flow_file: str         # <run_dir>/workspace/<flow_file_path>


_APP_BINARY_NAME = {
    "ANDROID": "app.apk",
    "IOS": "app.ipa",
}


def read_run_folder(path: str) -> RunSpec:
    metadata_path = os.path.join(path, "metadata.json")
    if not os.path.isfile(metadata_path):
        raise FileNotFoundError(f"missing metadata.json in run folder: {path}")

    with open(metadata_path) as f:
        metadata = json.load(f)

    run_id = metadata.get("run_id") or os.path.basename(os.path.normpath(path))

    platform = str(metadata["platform"]).upper()
    if platform not in _APP_BINARY_NAME:
        raise ValueError(
            f"unsupported platform {platform!r} in {metadata_path}; "
            f'expected "ANDROID" or "IOS"'
        )

    # A run folder may legitimately have no app binary at all — a built-in-app
    # flow (e.g. com.android.settings / com.apple.Preferences) targets an app
    # that's already on the device and installs nothing. Presence on disk is
    # the signal: if app.apk/app.ipa isn't there, treat this as a no-install
    # RunSpec rather than erroring. The real-corpus path (app binary present)
    # is unchanged — it's still required to exist once app_binary is set.
    app_binary_path = os.path.join(path, _APP_BINARY_NAME[platform])
    app_binary = app_binary_path if os.path.isfile(app_binary_path) else None

    workspace_dir = os.path.join(path, "workspace")

    flow_file = os.path.join(workspace_dir, metadata["flow_file_path"])
    if not os.path.isfile(flow_file):
        raise RuntimeError(f"missing flow file: {flow_file}")

    device_spec = dict(metadata.get("device_spec") or {})
    device_spec.setdefault("locale", None)

    env = metadata.get("env") or {}

    return RunSpec(
        run_id=run_id,
        run_dir=path,
        platform=platform,
        package_id=metadata["package_id"],
        device_spec=device_spec,
        env=env,
        workspace_dir=workspace_dir,
        app_binary=app_binary,
        flow_file=flow_file,
    )


def expand_folders(globs: list) -> list:
    found = set()
    for pattern in globs:
        for candidate in glob.glob(pattern):
            if os.path.isdir(candidate) and os.path.isfile(
                os.path.join(candidate, "metadata.json")
            ):
                found.add(candidate)
    return sorted(found)
