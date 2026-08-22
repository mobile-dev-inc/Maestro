#!/usr/bin/env python3
"""run_gate.py — the corpus runner for the zero-divergence validation gate.

Drives the differential gate over the replay corpus: for each flow, run the
SAME flow on two CLIs — one built from the refactor branch (LegacyExecutionBackend,
"a"/legacy) and one from stock main ("b"/stock) — against ONE shared emulator,
resetting app state between the two runs so both backends start from identical
device state. Collect each run's per-step trace (trace/steps.jsonl, the schema
from Task 3.1/3.1b) and diff them with diff_traces.py.

The runner itself is orchestration only: it transfers each flow's app + workspace
to the remote host, runs both CLIs there, pulls the two traces back, and diffs
them LOCALLY with the tested diff_traces.py. The gate verdict is diff_traces.py's,
never this script's.

Design notes / decisions (see the ledger):
  * One reused AVD, `pm clear <pkg>` between the legacy and stock runs of the
    same flow → both backends see identical state. Faithful cloud replay (fresh
    AVD, matching API per flow) is a coverage refinement, not a gate requirement:
    an API mismatch fails a flow identically on both backends (no manufactured
    divergence), only shrinking how many commands it exercises. We log per-flow
    stepsCompared so coverage stays honest.
  * Per-flow env is baked verbatim from metadata.json into BOTH runs, so whatever
    maestro does with it (incl. the MAESTRO_ENV base64 blob) it does identically.
  * Sequential, one apk on the host at a time (deleted after), resumable.

Not in scope here: iOS flows (need arm-m4s + booted sims) — Android-first per the
plan; iOS is a follow-up pass with the same tool.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DIFF_TOOL = HERE / "diff_traces.py"

sys.path.insert(0, str(HERE))
import classify  # noqa: E402  (local, sits beside this file)

INVENTORY = "/Users/stevieclifton/codes/copilot/didb/infrastructure/macstadium/inventory/testing.yml"
CORPUS_INDEX = "/Users/stevieclifton/maestro-replay-harness/_index/corpus-index.json"

# CLI install roots on the host (staged during smoke; maestro=legacy, maestro-stock=stock).
REMOTE_STAGE = "~/dir-research-scratch/gate-smoke"
LEGACY_CLI = f"{REMOTE_STAGE}/maestro/bin/maestro"
STOCK_CLI = f"{REMOTE_STAGE}/maestro-stock/bin/maestro"
REMOTE_BASE = "~/dir-research-scratch/gate-corpus"
REMOTE_JAVA_HOME = "/opt/homebrew/opt/openjdk@17"
REMOTE_ADB = "~/android-sdk/platform-tools/adb"

SSH_OPTS = [
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    "-o", "ConnectTimeout=20",
    "-o", "ServerAliveInterval=30",
    "-o", "ServerAliveCountMax=6",
    "-o", "LogLevel=ERROR",
]


# ── host credentials ───────────────────────────────────────────────────────
def load_host(alias):
    import yaml
    inv = yaml.safe_load(open(INVENTORY))

    def walk(d, path=()):
        if isinstance(d, dict):
            for k, v in d.items():
                yield from walk(v, path + (k,))
        elif isinstance(d, list):
            for i, v in enumerate(d):
                yield from walk(v, path + (i,))

    hosts = {}
    for path, v in list(walk(inv)):
        pass
    # simpler: find the alias node under any hosts mapping
    def find(d):
        if isinstance(d, dict):
            if alias in d and isinstance(d[alias], dict) and "ansible_host" in d[alias]:
                return d[alias]
            for v in d.values():
                r = find(v)
                if r:
                    return r
        return None

    node = find(inv)
    if not node:
        raise SystemExit(f"host alias {alias!r} not found in {INVENTORY}")
    return {
        "host": node["ansible_host"],
        "user": node.get("ansible_user", "administrator"),
        "password": node["ansible_password"],
    }


# ── ssh/scp primitives (sshpass, literal -o flags) ─────────────────────────
class Remote:
    def __init__(self, host):
        self.host = host
        self.target = f"{host['user']}@{host['host']}"
        self.env = dict(os.environ, SSHPASS=host["password"])
        # scp uses SFTP mode and does NOT expand a leading ~ like the login
        # shell does — resolve $HOME once and make scp endpoints absolute.
        self.home = self.sh("printf %s \"$HOME\"", timeout=60).stdout.strip()

    def expand(self, remote):
        if remote == "~":
            return self.home
        if remote.startswith("~/"):
            return self.home + remote[1:]
        return remote

    def sh(self, script, timeout=None, check=True):
        """Run a bash script on the host. Returns CompletedProcess."""
        cmd = ["sshpass", "-e", "ssh", *SSH_OPTS, self.target, "bash -s"]
        cp = subprocess.run(
            cmd, input=script, env=self.env, text=True,
            capture_output=True, timeout=timeout,
        )
        if check and cp.returncode != 0:
            raise RuntimeError(
                f"remote sh failed (rc={cp.returncode})\n--stdout--\n{cp.stdout}\n--stderr--\n{cp.stderr}"
            )
        return cp

    def put(self, local, remote, timeout=None):
        remote = self.expand(remote)
        cmd = ["sshpass", "-e", "scp", *SSH_OPTS, str(local), f"{self.target}:{remote}"]
        cp = subprocess.run(cmd, env=self.env, text=True, capture_output=True, timeout=timeout)
        if cp.returncode != 0:
            raise RuntimeError(f"scp put failed (rc={cp.returncode}): {cp.stderr}")

    def get(self, remote, local, timeout=None):
        remote = self.expand(remote)
        cmd = ["sshpass", "-e", "scp", *SSH_OPTS, f"{self.target}:{remote}", str(local)]
        cp = subprocess.run(cmd, env=self.env, text=True, capture_output=True, timeout=timeout)
        return cp.returncode == 0


# ── work-list ──────────────────────────────────────────────────────────────
def android_worklist():
    idx = json.load(open(CORPUS_INDEX))
    flows = [f for f in idx["flows"] if f["platform"] == "ANDROID"]
    items = []
    for f in flows:
        rd = f["runDir"]
        md = json.load(open(os.path.join(rd, "metadata.json")))
        key = f"{f['org']}_{f['runId']}".replace("/", "_").replace(" ", "_")
        items.append({
            "key": key,
            "org": f["org"],
            "runDir": rd,
            "flowFilePath": f["flowFilePath"],
            "package": md["package_id"],
            "env": md.get("env") or {},
            "androidOs": f["deviceSpec"].get("os"),
        })
    return items


def flow_basename(flow_file_path):
    return Path(flow_file_path).stem


# ── the remote per-flow runner (generated, scp'd, executed) ────────────────
def build_remote_script(item, serial, run_timeout, sides, adb, base_abs):
    """Emit a self-contained bash script that runs N CLI passes on the host.

    `sides` is a list of (cli_abs, outname) — one maestro run each, in order,
    each preceded by `pm clear` so every pass starts from identical app state.

    All host paths (base, CLIs, adb) must be ABSOLUTE — shlex.quote wraps any
    ~ path in single quotes, where bash won't expand the tilde (it becomes a
    literal ~ dir). Callers resolve ~ to $HOME before passing them in.
    """
    env = item["env"]
    env_args = []
    for k, v in env.items():
        env_args.append("-e")
        env_args.append(f"{k}={v}")
    env_quoted = " ".join(shlex.quote(a) for a in env_args)
    pkg = shlex.quote(item["package"])
    flow = shlex.quote(item["flowFilePath"])
    base = shlex.quote(base_abs)
    run_lines = "\n".join(
        f"run_side {shlex.quote(cli)} {shlex.quote(outname)}" for cli, outname in sides
    )
    find_lines = "\n".join(
        f'find "$BASE/{outname}" -name steps.jsonl 2>/dev/null | while read f; do echo "{outname} $(wc -l < "$f") $f"; done'
        for _, outname in sides
    )

    return f"""#!/usr/bin/env bash
set -uo pipefail
export JAVA_HOME={REMOTE_JAVA_HOME}
export PATH="$JAVA_HOME/bin:$PATH"
export MAESTRO_CLI_NO_ANALYTICS=true
export MAESTRO_STEP_TRACE=1
ADB={shlex.quote(adb)}
SERIAL={shlex.quote(serial)}
BASE={base}
PKG={pkg}
FLOW={flow}
ENV_ARGS=({env_quoted})

cd "$BASE"
echo "== untar payload =="
tar -xf payload.tar
cd "$BASE/workspace"

# Portable timeout: macOS ships no `timeout`/`gtimeout`. Background the job,
# watchdog TERM→KILL, return the job's real exit code.
run_with_timeout() {{
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill -TERM "$pid" 2>/dev/null; sleep 5; kill -KILL "$pid" 2>/dev/null ) 2>/dev/null &
  local watcher=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null
  return $rc
}}

run_side() {{
  local cli="$1"; local out="$2"
  echo "== pm clear $PKG =="
  "$ADB" -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1 || true
  echo "== run $out =="
  run_with_timeout {run_timeout} "$cli" --device "$SERIAL" test \
      --debug-output="$BASE/$out" --flatten-debug-output \
      "${{ENV_ARGS[@]}}" "$FLOW" > "$BASE/$out.log" 2>&1
  echo "  exit=$? ($out)"
}}

echo "== install =="
"$ADB" -s "$SERIAL" install -r "$BASE/app.apk" 2>&1 | tail -2

{run_lines}

echo "== uninstall =="
"$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true

echo "== traces =="
{find_lines}
echo "== done =="
"""


# ── per-flow execution ─────────────────────────────────────────────────────
def resolve_sides(remote, mode):
    """Return [(cli_abs, outname, local_subdir)] for the run mode.

    gate   : legacy vs stock (2 runs) — subdirs a, b
    triple : stock, legacy, stock (3 runs) — subdirs s1, l, s2 (legacy bracketed)
    control-stock / control-legacy : same cli twice — subdirs a, b
    """
    legacy = remote.expand(LEGACY_CLI)
    stock = remote.expand(STOCK_CLI)
    if mode == "quad":
        return [(stock, "out-s1", "s1"), (legacy, "out-l1", "l1"),
                (stock, "out-s2", "s2"), (legacy, "out-l2", "l2")]
    if mode == "triple":
        return [(stock, "out-s1", "s1"), (legacy, "out-l", "l"), (stock, "out-s2", "s2")]
    if mode == "control-stock":
        return [(stock, "out-legacy", "a"), (stock, "out-stock", "b")]
    if mode == "control-legacy":
        return [(legacy, "out-legacy", "a"), (legacy, "out-stock", "b")]
    return [(legacy, "out-legacy", "a"), (stock, "out-stock", "b")]


def run_flow(remote, item, serial, traces_dir, run_timeout, scp_timeout, ssh_timeout, log,
             mode="gate", sides_override=None):
    key = item["key"]
    fbn = flow_basename(item["flowFilePath"])
    local_flow_dir = traces_dir / key
    sides = sides_override if sides_override is not None else resolve_sides(remote, mode)
    for _, _, sub in sides:
        (local_flow_dir / sub).mkdir(parents=True, exist_ok=True)

    # 1. tar app.apk + workspace locally
    with tempfile.TemporaryDirectory() as td:
        tar_path = Path(td) / "payload.tar"
        subprocess.run(
            ["tar", "-C", item["runDir"], "-cf", str(tar_path), "app.apk", "workspace"],
            check=True,
        )
        remote_base = remote.expand(f"{REMOTE_BASE}/{key}")
        adb = remote.expand(REMOTE_ADB)
        script_sides = [(cli, outname) for cli, outname, _ in sides]
        script_path = Path(td) / "run.sh"
        script_path.write_text(
            build_remote_script(item, serial, run_timeout, script_sides, adb, remote_base)
        )

        remote.sh(f"mkdir -p {shlex.quote(remote_base)}", timeout=ssh_timeout)
        # 2. transfer payload + script
        sz = tar_path.stat().st_size // (1024 * 1024)
        log(f"    scp payload ({sz}MB)…")
        remote.put(tar_path, f"{remote_base}/payload.tar", timeout=scp_timeout)
        remote.put(script_path, f"{remote_base}/run.sh", timeout=ssh_timeout)

    # 3. run all passes on host
    log(f"    running {len(sides)} passes (timeout {run_timeout}s each)…")
    cp = remote.sh(f"bash {shlex.quote(remote_base)}/run.sh",
                   timeout=run_timeout * len(sides) + 600, check=False)
    remote_stdout = cp.stdout

    # 4. pull each pass's trace + log
    def pull(side_out, dest):
        find = remote.sh(
            f"find {shlex.quote(remote_base)}/{side_out} -name steps.jsonl 2>/dev/null | head -1",
            timeout=ssh_timeout, check=False,
        )
        rp = find.stdout.strip()
        if not rp:
            return False
        return remote.get(rp, str(dest), timeout=scp_timeout)

    pulled = {}
    for _, outname, sub in sides:
        pulled[sub] = pull(outname, local_flow_dir / sub / "steps.jsonl")
        remote.get(f"{remote_base}/{outname}.log", str(local_flow_dir / f"{outname}.log"), timeout=scp_timeout)

    # 5. cleanup host
    remote.sh(f"rm -rf {shlex.quote(remote_base)}", timeout=ssh_timeout, check=False)

    return {
        "key": key,
        "org": item["org"],
        "flow": fbn,
        "androidOs": item["androidOs"],
        "pulled": pulled,
        "remoteStdout": remote_stdout,
    }


# ── local classification helpers ───────────────────────────────────────────
def classify_dir(flow_dir, tol):
    """Classify a flow from whatever s*/l* run subdirs it currently has."""
    stocks, legacies = classify._discover(Path(flow_dir))
    if not stocks or not legacies:
        return None
    return classify.classify_flow_files(stocks, legacies, tol=tol)


def escalation_sides(remote, flow_dir, n_each):
    """Sides that ADD n_each stock + n_each legacy runs, numbered past the
    highest existing s*/l* index so they never clobber prior runs."""
    existing = [p.name for p in Path(flow_dir).iterdir() if p.is_dir()] if Path(flow_dir).exists() else []
    def nextn(prefix):
        nums = [int(n[1:]) for n in existing if n.startswith(prefix) and n[1:].isdigit()]
        return (max(nums) + 1) if nums else 1
    stock = remote.expand(STOCK_CLI)
    legacy = remote.expand(LEGACY_CLI)
    sides = []
    sn, ln = nextn("s"), nextn("l")
    for i in range(n_each):
        sides.append((stock, f"out-s{sn+i}", f"s{sn+i}"))
        sides.append((legacy, f"out-l{ln+i}", f"l{ln+i}"))
    return sides


def diff_pair(a, b, tol):
    cp = subprocess.run(
        [sys.executable, str(DIFF_TOOL), "--a", str(a), "--b", str(b), "--tol", str(tol), "--json"],
        capture_output=True, text=True,
    )
    try:
        result = json.loads(cp.stdout) if cp.stdout.strip() else None
    except json.JSONDecodeError:
        result = None
    return cp.returncode, result, cp.stderr


def main(argv=None):
    ap = argparse.ArgumentParser(description="Corpus runner for the zero-divergence gate (Android).")
    ap.add_argument("--host-alias", default="arm-m2m-006")
    ap.add_argument("--serial", default="emulator-5680")
    ap.add_argument("--traces-dir", default=str(HERE / "gate-traces"))
    ap.add_argument("--limit", type=int, default=None, help="run at most N flows")
    ap.add_argument("--only", default=None, help="substring filter on flow key")
    ap.add_argument("--tol", type=int, default=2)
    ap.add_argument("--run-timeout", type=int, default=900, help="per-CLI-run timeout (s)")
    ap.add_argument("--scp-timeout", type=int, default=1200)
    ap.add_argument("--ssh-timeout", type=int, default=120)
    ap.add_argument("--resume", action="store_true", help="skip flows whose traces already exist")
    ap.add_argument("--mode", choices=["quad", "triple", "gate", "control-stock", "control-legacy"],
                    default="quad",
                    help="quad=stock/legacy/stock/legacy flakiness-robust gate (default); "
                         "triple=stock/legacy/stock; gate=legacy-vs-stock 2-run; "
                         "control-*=same cli twice")
    ap.add_argument("--escalate-rounds", type=int, default=2,
                    help="quad/triple: for each RED flow, add 2+2 runs and re-classify, up to N "
                         "rounds — a coin-flip step surfaces its own flakiness with more samples; "
                         "a real divergence stays RED. 0 disables.")
    args = ap.parse_args(argv)

    host = load_host(args.host_alias)
    remote = Remote(host)
    traces_dir = Path(args.traces_dir)
    traces_dir.mkdir(parents=True, exist_ok=True)
    subs_needed = [sub for _, _, sub in resolve_sides(remote, args.mode)]

    items = android_worklist()
    if args.only:
        subs = [s for s in args.only.split(",") if s]
        items = [i for i in items if any(s in i["key"] for s in subs)]
    if args.limit:
        items = items[: args.limit]

    def log(msg):
        print(msg, flush=True)

    log(f"[gate] host={host['host']} serial={args.serial} mode={args.mode} flows={len(items)} tol={args.tol}")

    per_flow = []
    for n, item in enumerate(items, 1):
        key = item["key"]
        flow_dir = traces_dir / key
        traces = {sub: flow_dir / sub / "steps.jsonl" for sub in subs_needed}
        log(f"[{n}/{len(items)}] {key}  ({item['androidOs']}, {item['flowFilePath']})")

        if args.resume and all(p.exists() for p in traces.values()):
            log("    resume: traces present, skipping run")
        else:
            t0 = time.time()
            try:
                r = run_flow(remote, item, args.serial, traces_dir,
                             args.run_timeout, args.scp_timeout, args.ssh_timeout, log,
                             mode=args.mode)
                log(f"    ran in {int(time.time()-t0)}s  pulled={r['pulled']}")
            except Exception as e:
                log(f"    ERROR running flow: {e}")

        rec = {"key": key, "org": item["org"], "flow": flow_basename(item["flowFilePath"]),
               "androidOs": item["androidOs"]}
        have_all = all(p.exists() for p in traces.values())
        if args.mode in ("triple", "quad"):
            if have_all:
                stock_paths = [traces[s] for s in subs_needed if s.startswith("s")]
                legacy_paths = [traces[s] for s in subs_needed if s.startswith("l")]
                try:
                    c = classify.classify_flow_files(stock_paths, legacy_paths, tol=args.tol)
                    rec.update(status="GREEN" if c["green"] else "RED",
                               judged=c["judgedSteps"], excluded=c["excludedSteps"],
                               kFlaky=c["kFlaky"], coordFlags=len(c["coordFlags"]),
                               realDivergenceStep=c["realDivergenceStep"],
                               realDivergenceDetail=c["realDivergenceDetail"])
                except classify.TraceError as e:
                    rec.update(status="trace-error", detail=str(e))
            else:
                missing = [s for s, p in traces.items() if not p.exists()]
                rec.update(status="incomplete", missing=missing)
        else:  # gate / control 2-run modes
            a_out, b_out = traces["a"], traces["b"]
            if a_out.exists() and b_out.exists():
                rc, result, err = diff_pair(a_out, b_out, args.tol)
                if result is None:
                    rec.update(status="diff-error", detail=err.strip())
                else:
                    rec.update(status="zero-divergence" if not result["divergences"] else "DIVERGENT",
                               stepsCompared=result["stepsCompared"],
                               divergences=len(result["divergences"]),
                               firstDivergentStep=result["firstDivergentStep"])
                    if result["divergences"]:
                        rec["divergenceDetail"] = result["divergences"][:5]
            elif a_out.exists() != b_out.exists():
                rec.update(status="ASYMMETRIC-TRACE")
            else:
                rec.update(status="no-trace")
        per_flow.append(rec)
        extra = ""
        if rec.get("judged") is not None:
            extra = f" (judged {rec['judged']}, excluded {rec['excluded']}, kFlaky {rec['kFlaky']})"
        elif rec.get("stepsCompared") is not None:
            extra = f" ({rec['stepsCompared']} steps)"
        log(f"    => {rec['status']}{extra}")

    # escalate RED flows: add samples until flakiness surfaces (→GREEN) or the
    # divergence proves reproducible (stays RED). Records the escalation trail.
    if args.mode in ("triple", "quad") and args.escalate_rounds > 0:
        item_by_key = {it["key"]: it for it in items}
        for rnd in range(1, args.escalate_rounds + 1):
            reds = [r for r in per_flow if r["status"] == "RED"]
            if not reds:
                break
            log(f"\n[escalate round {rnd}] {len(reds)} RED flow(s): +2 stock +2 legacy each")
            for rec in reds:
                item = item_by_key.get(rec["key"])
                if not item:
                    continue
                flow_dir = traces_dir / rec["key"]
                sides = escalation_sides(remote, flow_dir, 2)
                log(f"  {rec['key']}: adding {[s[2] for s in sides]}")
                try:
                    run_flow(remote, item, args.serial, traces_dir,
                             args.run_timeout, args.scp_timeout, args.ssh_timeout, log,
                             mode=args.mode, sides_override=sides)
                except Exception as e:
                    log(f"    ERROR escalating: {e}")
                    continue
                c = classify_dir(flow_dir, args.tol)
                if c is None:
                    continue
                rec["escalated"] = rec.get("escalated", 0) + 1
                rec.update(status="GREEN" if c["green"] else "RED",
                           judged=c["judgedSteps"], excluded=c["excludedSteps"],
                           kFlaky=c["kFlaky"], coordFlags=len(c["coordFlags"]),
                           realDivergenceStep=c["realDivergenceStep"],
                           realDivergenceDetail=c["realDivergenceDetail"])
                log(f"    => {rec['status']} after escalation (judged {c['judgedSteps']}, excluded {c['excludedSteps']})")

    # aggregate
    from collections import Counter
    summary = {"mode": args.mode, "totalFlows": len(per_flow),
               "byStatus": dict(Counter(f["status"] for f in per_flow))}
    if args.mode in ("triple", "quad"):
        greens = [f for f in per_flow if f["status"] == "GREEN"]
        reds = [f for f in per_flow if f["status"] == "RED"]
        summary.update(
            corpusGreen=(not reds and all(f["status"] in ("GREEN",) for f in per_flow)),
            flowsGreen=len(greens), flowsRed=len(reds),
            totalJudgedSteps=sum(f.get("judged", 0) for f in per_flow),
            totalExcludedSteps=sum(f.get("excluded", 0) for f in per_flow),
            totalCoordFlags=sum(f.get("coordFlags", 0) for f in per_flow),
            redFlows=[{"flow": f["key"], "step": f.get("realDivergenceStep"),
                       "detail": f.get("realDivergenceDetail")} for f in reds],
        )
    report_path = traces_dir / "gate-report.json"
    report_path.write_text(json.dumps({"summary": summary, "flows": per_flow}, indent=2))
    log("\n" + "=" * 60)
    log(json.dumps(summary, indent=2))
    log(f"[gate] report written: {report_path}")
    if args.mode in ("triple", "quad"):
        return 0 if summary["corpusGreen"] else 1
    bad = [f for f in per_flow if f["status"] in ("DIVERGENT", "ASYMMETRIC-TRACE", "diff-error")]
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
