"""flow_copy.py — copy the flow (+ its runFlow subflows) into results, scrubbed.

The APK/IPA stays referenced by content hash (source.json), but the small,
human-readable part — the flow yaml and every subflow it transitively pulls in —
is copied into out/<runId>/flow/ so triage is self-contained. Every copy is
scrubbed of the per-run env secret VALUES first: a corpus metadata.json's env
holds real API tokens/keys, and nothing shippable may carry them (spec
exit-check 8: zero corpus tokens in the bundle). Stdlib only.
"""
from __future__ import annotations
import os, re

_REDACTED = "***REDACTED***"
_RUNFLOW_RE = re.compile(r"runFlow:\s*['\"]?([^\s'\"]+\.ya?ml)")


def scrub_flow(text: str, secrets) -> str:
    for s in secrets:
        if s:
            text = text.replace(s, _REDACTED)
    return text


def collect_subflows(flow_file: str, workspace_dir: str) -> list:
    seen, order = set(), []
    stack = [flow_file]
    while stack:
        f = stack.pop(0)
        if f in seen or not os.path.isfile(f):
            continue
        seen.add(f); order.append(f)
        text = open(f).read()
        for m in _RUNFLOW_RE.finditer(text):
            ref = m.group(1)
            cand = os.path.normpath(os.path.join(os.path.dirname(f), ref))
            if not os.path.isfile(cand):
                cand = os.path.normpath(os.path.join(workspace_dir, ref))
            stack.append(cand)
    return order


def copy_flow_scrubbed(run_out_dir, flow_file, workspace_dir, secrets) -> list:
    flow_dir = os.path.join(run_out_dir, "flow")
    os.makedirs(flow_dir, exist_ok=True)
    written = []
    for f in collect_subflows(flow_file, workspace_dir):
        rel = os.path.relpath(f, workspace_dir)
        dest = os.path.join(flow_dir, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w") as fh:
            fh.write(scrub_flow(open(f).read(), secrets))
        written.append(dest)
    return written
