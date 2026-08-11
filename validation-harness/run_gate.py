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
from executor import Remote, load_host, run_cli, INVENTORY_ENV as _INVENTORY_ENV  # noqa: E402

# No corpus-index path is hardcoded — every operator's replay-harness corpus
# lives somewhere different. Pass one explicitly (--corpus-index) or set this
# env var; android_worklist() fails fast if neither is supplied.
CORPUS_INDEX_ENV = "MAESTRO_HARNESS_CORPUS_INDEX"

# CLI install roots on the host (staged during smoke; maestro=legacy, maestro-stock=stock).
REMOTE_STAGE = "~/dir-research-scratch/gate-smoke"
LEGACY_CLI = f"{REMOTE_STAGE}/maestro/bin/maestro"
STOCK_CLI = f"{REMOTE_STAGE}/maestro-stock/bin/maestro"
REMOTE_BASE = "~/dir-research-scratch/gate-corpus"
REMOTE_ADB = "~/android-sdk/platform-tools/adb"


# ── work-list ──────────────────────────────────────────────────────────────
def android_worklist(corpus_index_path=None):
    path = corpus_index_path or os.environ.get(CORPUS_INDEX_ENV)
    if not path:
        raise SystemExit(
            f"corpus index path required: pass --corpus-index, or set {CORPUS_INDEX_ENV}"
        )
    idx = json.load(open(path))
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
    """Run every side for one flow against the ONE shared `serial` emulator.

    Every CLI pass — build the env/watchdog script, run it, pull steps.jsonl —
    goes through the shared `executor.run_cli` helper (the same one
    run_differential.py uses), driven here through `remote` (a `Remote`
    instance from executor.py — the same transport the executor seam wraps,
    not a private copy of it). No script generation lives in this module
    anymore; only the per-flow orchestration (stage payload once, reset +
    run_cli per side, install/uninstall once) does.
    """
    key = item["key"]
    fbn = flow_basename(item["flowFilePath"])
    local_flow_dir = traces_dir / key
    sides = sides_override if sides_override is not None else resolve_sides(remote, mode)
    for _, _, sub in sides:
        (local_flow_dir / sub).mkdir(parents=True, exist_ok=True)

    # 1. tar app.apk + workspace locally, transfer + untar on the host.
    with tempfile.TemporaryDirectory() as td:
        tar_path = Path(td) / "payload.tar"
        subprocess.run(
            ["tar", "-C", item["runDir"], "-cf", str(tar_path), "app.apk", "workspace"],
            check=True,
        )
        remote_base = remote.expand(f"{REMOTE_BASE}/{key}")
        adb = remote.expand(REMOTE_ADB)

        remote.sh(f"mkdir -p {shlex.quote(remote_base)}", timeout=ssh_timeout)
        sz = tar_path.stat().st_size // (1024 * 1024)
        log(f"    scp payload ({sz}MB)…")
        remote.put(tar_path, f"{remote_base}/payload.tar", timeout=scp_timeout)
    remote.sh(
        f"cd {shlex.quote(remote_base)} && tar -xf payload.tar",
        timeout=ssh_timeout,
    )

    pkg = item["package"]
    flow_remote = f"{remote_base}/workspace/{item['flowFilePath']}"
    env_args = []
    for k, v in item["env"].items():
        env_args += ["-e", f"{k}={v}"]
    env_args_str = " ".join(shlex.quote(a) for a in env_args)

    # 2. install the app ONCE, shared by every side.
    log("    install…")
    remote.sh(
        f"{shlex.quote(adb)} -s {shlex.quote(serial)} install -r "
        f"{shlex.quote(remote_base + '/app.apk')}",
        timeout=scp_timeout, check=False,
    )

    # 3. run every side: reset app state, then run + pull through the shared
    # executor.run_cli helper — the JAVA_HOME/PATH/MAESTRO_STEP_TRACE=1
    # preamble, the portable run_with_timeout watchdog, and the
    # find…steps.jsonl pull all live there now, not duplicated here.
    log(f"    running {len(sides)} passes (timeout {run_timeout}s each)…")
    pulled = {}
    for cli, outname, sub in sides:
        remote.sh(
            f"{shlex.quote(adb)} -s {shlex.quote(serial)} shell pm clear {shlex.quote(pkg)} "
            f">/dev/null 2>&1 || true",
            timeout=ssh_timeout, check=False,
        )
        dbg = f"{remote_base}/{outname}"
        local_trace = local_flow_dir / sub / "steps.jsonl"
        pulled[sub] = run_cli(
            remote, cli=cli, device_id=serial, platform="ANDROID",
            dbg=dbg, flow_remote=flow_remote, local_trace_path=str(local_trace),
            env_args_str=env_args_str, timeout=run_timeout,
        )

    # 4. cleanup: uninstall + remove the remote workdir.
    remote.sh(
        f"{shlex.quote(adb)} -s {shlex.quote(serial)} uninstall {shlex.quote(pkg)} >/dev/null 2>&1 || true",
        timeout=ssh_timeout, check=False,
    )
    remote.sh(f"rm -rf {shlex.quote(remote_base)}", timeout=ssh_timeout, check=False)

    return {
        "key": key,
        "org": item["org"],
        "flow": fbn,
        "androidOs": item["androidOs"],
        "pulled": pulled,
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
    ap.add_argument("--host-alias", required=True, help="remote host alias, looked up in --inventory")
    ap.add_argument("--serial", required=True, help="the shared emulator's adb serial (e.g. emulator-5680)")
    ap.add_argument("--inventory",
                    help=f"host inventory YAML; falls back to ${_INVENTORY_ENV}")
    ap.add_argument("--corpus-index",
                    help=f"replay-harness corpus-index.json; falls back to ${CORPUS_INDEX_ENV}")
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

    host = load_host(args.host_alias, inventory_path=args.inventory)
    remote = Remote(host)
    traces_dir = Path(args.traces_dir)
    traces_dir.mkdir(parents=True, exist_ok=True)
    subs_needed = [sub for _, _, sub in resolve_sides(remote, args.mode)]

    items = android_worklist(args.corpus_index)
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
