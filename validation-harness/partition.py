"""partition.py — pure corpus partitioning by platform.

Classifies each run folder from its metadata.json (json.load, never grep —
the corpus has indentation drift and an uppercase device_spec 'OS' key in a
couple of folders), then round-robins each platform's folders evenly across
that platform's hosts. Even split, not spec-clustering. Stdlib only.
"""
from __future__ import annotations

import json
import os

_SUPPORTED = {"ANDROID", "IOS"}


def classify_platform(metadata: dict) -> str:
    raw = metadata.get("platform")
    if raw is None:
        raise ValueError("metadata has no 'platform' field")
    platform = str(raw).upper()
    if platform not in _SUPPORTED:
        raise ValueError(f"unsupported platform {platform!r}; expected ANDROID or IOS")
    return platform


def folder_platform(folder_path: str) -> str:
    with open(os.path.join(folder_path, "metadata.json")) as fh:
        return classify_platform(json.load(fh))


def partition(classified, ios_hosts, android_hosts):
    result = {h: [] for h in list(ios_hosts) + list(android_hosts)}
    buckets = {"ANDROID": [f for f, p in classified if p == "ANDROID"],
               "IOS": [f for f, p in classified if p == "IOS"]}
    hosts_for = {"ANDROID": list(android_hosts), "IOS": list(ios_hosts)}
    for platform, folders in buckets.items():
        hosts = hosts_for[platform]
        if folders and not hosts:
            raise ValueError(f"{len(folders)} {platform} folders but no {platform} hosts assigned")
        for i, folder in enumerate(folders):
            result[hosts[i % len(hosts)]].append(folder)
    return result
