"""flow_copy.py — copy the flow (+ its runFlow subflows) into results, scrubbed.

The APK/IPA stays referenced by content hash (source.json), but the small,
human-readable part — the flow yaml and every subflow it transitively pulls in —
is copied into out/<runId>/flow/ so triage is self-contained. Every copy is
scrubbed of the per-run env secret VALUES first: a corpus metadata.json's env
holds real API tokens/keys, and nothing shippable may carry them (spec
exit-check 8: zero corpus tokens in the bundle). Stdlib only.

Scrub scope: the scrub is env-value-driven. It redacts the known corpus `env`
VALUES — the corpus convention is `${VAR}` interpolation with values injected at
runtime, so those values are the secrets we know about. A secret hardcoded
literally inline in a flow (not present in `env`) is out of scope: we have no
value to match on and cannot distinguish it from ordinary flow text.
"""
from __future__ import annotations
import os, re

_REDACTED = "***REDACTED***"
# Inline form: `- runFlow: sub.yaml`. Block form: `- runFlow:` on its own line
# followed by an indented `file: sub.yaml` (possibly alongside env: and friends).
_RUNFLOW_RE = re.compile(
    r"runFlow:\s*['\"]?([^\s'\"]+\.ya?ml)"          # inline form
    r"|^\s+file:\s*['\"]?([^\s'\"]+\.ya?ml)",       # block form's file: key
    re.MULTILINE,
)


def scrub_flow(text: str, secrets) -> str:
    """Redact each known corpus env secret VALUE from `text`.

    Env values can be non-strings (JSON numbers/booleans) and empty/None. Empty
    and None are skipped; anything else is coerced to str before replacing, so a
    numeric env value can't crash the replace. See the scrub-scope note above:
    this redacts env VALUES only, not literals hardcoded inline in a flow.
    """
    for s in secrets:
        if s:
            text = text.replace(str(s), _REDACTED)
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
            ref = m.group(1) or m.group(2)   # inline form | block-form file:
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
