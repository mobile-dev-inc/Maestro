#!/usr/bin/env python3
# NOTE: The general legacy-vs-device-core differential over replay-harness folders is
# run_differential.py (exact-spec device boot, local OR remote, video, per-step diff).
# This file remains the single-flow built-in-app fidelity demo and exports fidelity_report(),
# which run_differential.py reuses. See RUN_DIFFERENTIAL.md.
"""Phase 5 — device-core fidelity framework.

The question the device-core validation program exists to answer: *where does
device-core agree with maestro, and where does it not?* This runs ONE flow
twice on a shared emulator — once on the legacy backend (the proven oracle) and
once on device-core solo (MAESTRO_DEVICECORE_ASSERT=1) — pulls both per-step
traces, and classifies every step:

  - SERVED + AGREE     : device-core ran the verb and produced maestro's verdict
                         + chosen element (verdict exact, element identity exact,
                         coords within tol). This is the fidelity signal.
  - SERVED + DIVERGE   : device-core ran the verb but disagreed with legacy.
                         A real fidelity failure.
  - OWED (coverage gap): device-core declined the verb (not implemented yet).
                         Not a divergence — it's what device-core can't test yet.

`reachDepth` = how many steps device-core got through before the flow ended or a
served step FAILed/ERRORed. As device-core gains verbs, OWED steps become SERVED
and this report answers the agreement question at ever-greater depth — the
framework doesn't change, only the numbers do.

The comparison core is diff_traces.diff_flow (a=legacy oracle, b=device-core):
declined steps are coverage gaps, everything else is verdict+identity+coord
checked. This runner adds the two-backend device execution + the fidelity framing.

Deterministic flow => a single legacy vs single device-core pass is clean (no
quad control needed; that machinery exists in run_gate.py for the flaky corpus).

Usage:
  phase5_fidelity.py --flow flows/settings-fidelity.yaml --appid com.android.settings
  # optional: --host-alias arm-m2m-006 --serial emulator-5680 --no-install (default for built-in apps)
"""
import argparse
import json
import shlex
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from executor import load_host, Remote  # noqa: E402
from run_gate import REMOTE_BASE, REMOTE_ADB, REMOTE_JAVA_HOME, LEGACY_CLI  # noqa: E402
import diff_traces  # noqa: E402

# The one branch CLI carries BOTH backends; the env var selects device-core. So
# both sides run the SAME cli — legacy = no env var (maestro AndroidDriver),
# device-core = MAESTRO_DEVICECORE_ASSERT=1 (device-core, no UiAutomation clash).
SIDES = [
    # (subdir, env_devicecore)
    ("legacy", False),
    ("devicecore", True),
]


def build_remote_script(cli, appid, flow_rel, serial, adb, base_abs, run_timeout, devicecore, out):
    """One CLI pass against an ALREADY-INSTALLED app (built-in for Settings).

    No apk install/uninstall — built-in apps are always present. `pm clear` still
    runs so each pass starts from identical app state. device-core needs adb ON
    PATH (it shells out to a bare `adb` during provisioning) + the env var.
    """
    dc_env = "export MAESTRO_DEVICECORE_ASSERT=1" if devicecore else "# legacy: no device-core env var"
    return f"""#!/usr/bin/env bash
set -uo pipefail
export JAVA_HOME={REMOTE_JAVA_HOME}
export PATH="$JAVA_HOME/bin:$HOME/android-sdk/platform-tools:$PATH"
export MAESTRO_CLI_NO_ANALYTICS=true
export MAESTRO_STEP_TRACE=1
{dc_env}
ADB={shlex.quote(adb)}
SERIAL={shlex.quote(serial)}
BASE={shlex.quote(base_abs)}
APPID={shlex.quote(appid)}
FLOW={shlex.quote(flow_rel)}

cd "$BASE"
echo "== pm clear $APPID =="
"$ADB" -s "$SERIAL" shell pm clear "$APPID" >/dev/null 2>&1 || true

run_with_timeout() {{
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill -TERM "$pid" 2>/dev/null; sleep 5; kill -KILL "$pid" 2>/dev/null ) 2>/dev/null &
  local w=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  return $rc
}}

echo "== run {out} (devicecore={str(devicecore).lower()}) =="
run_with_timeout {run_timeout} {shlex.quote(cli)} --device "$SERIAL" test \
    --debug-output="$BASE/{out}" --flatten-debug-output "$FLOW" > "$BASE/{out}.log" 2>&1
echo "  exit=$? ({out})"
echo "== trace =="
find "$BASE/{out}" -name steps.jsonl 2>/dev/null | while read f; do echo "{out} $(wc -l < "$f") $f"; done
echo "== done =="
"""


def run_side(remote, cli, appid, flow_rel, serial, adb, base_abs, run_timeout, devicecore, out,
             local_dir, scp_timeout, ssh_timeout, log):
    with tempfile.TemporaryDirectory() as td:
        sp = Path(td) / "run.sh"
        sp.write_text(build_remote_script(cli, appid, flow_rel, serial, adb, base_abs,
                                          run_timeout, devicecore, out))
        remote.put(sp, f"{base_abs}/run-{out}.sh", timeout=ssh_timeout)
    log(f"    running {out} (devicecore={devicecore})…")
    cp = remote.sh(f"bash {shlex.quote(base_abs)}/run-{out}.sh", timeout=run_timeout + 300, check=False)
    log(cp.stdout.strip())
    # pull trace + log
    (local_dir / out).mkdir(parents=True, exist_ok=True)
    rp = remote.sh(f"find {shlex.quote(base_abs)}/{out} -name steps.jsonl 2>/dev/null | head -1",
                   timeout=ssh_timeout, check=False).stdout.strip()
    pulled = False
    if rp:
        pulled = remote.get(rp, str(local_dir / out / "steps.jsonl"), timeout=scp_timeout)
    remote.get(f"{base_abs}/{out}.log", str(local_dir / f"{out}.log"), timeout=scp_timeout)
    return pulled


def fidelity_report(legacy_path, dc_path, tol, flow_name):
    """Reframe diff_flow (a=legacy oracle, b=device-core) as a fidelity report.

    load_steps returns {stepIndex: step} dicts, aligned by stepIndex. Legacy is
    the oracle spine; each of its steps is classified by what device-core did at
    the same index.
    """
    a = diff_traces.load_steps(str(legacy_path))   # {idx: step}
    b = diff_traces.load_steps(str(dc_path))        # {idx: step}
    diff = diff_traces.diff_flow(a, b, tol=tol, flow_name=flow_name)

    diverged_idx = {d.get("stepIndex") for d in diff["divergences"]}
    gap_idx = {g.get("stepIndex") for g in diff["coverageGaps"]}

    steps = []
    for idx in sorted(a):
        sa = a[idx]
        sb = b.get(idx)
        cmd = (sa.get("command") or {}).get("type", "?") if isinstance(sa.get("command"), dict) else "?"
        if sb is None:
            status = "MISSING"        # device-core run ended before this step (aborted upstream)
        elif idx in gap_idx or sb.get("declined"):
            status = "OWED"           # coverage gap — device-core declined (verb not implemented)
        elif idx in diverged_idx:
            status = "DIVERGE"        # served but disagreed with legacy
        else:
            status = "AGREE"          # served + matched legacy (verdict + element identity + coords)
        steps.append({
            "stepIndex": idx, "command": cmd, "status": status,
            "legacyVerdict": sa.get("verdict"),
            "deviceCoreVerdict": (sb or {}).get("verdict"),
        })

    served = [s for s in steps if s["status"] in ("AGREE", "DIVERGE")]
    return {
        "flow": flow_name,
        "deviceCoreSteps": len(b),        # how far device-core got before stopping
        "totalLegacySteps": len(a),
        "served": len(served),
        "agree": sum(1 for s in steps if s["status"] == "AGREE"),
        "diverge": sum(1 for s in steps if s["status"] == "DIVERGE"),
        "owedCoverageGaps": sum(1 for s in steps if s["status"] == "OWED"),
        "missing": sum(1 for s in steps if s["status"] == "MISSING"),
        "fidelityGreen": diff["divergences"] == [],   # served steps all agree
        "steps": steps,
        "rawDiff": diff,
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description="device-core fidelity framework (Phase 5).")
    ap.add_argument("--flow", required=True, help="local flow YAML to run on both backends")
    ap.add_argument("--appid", required=True, help="app under test (e.g. com.android.settings)")
    ap.add_argument("--host-alias", default="arm-m2m-006")
    ap.add_argument("--serial", default="emulator-5680")
    ap.add_argument("--traces-dir", default=str(HERE / "phase5-fidelity"))
    ap.add_argument("--tol", type=int, default=2)
    ap.add_argument("--run-timeout", type=int, default=600)
    ap.add_argument("--scp-timeout", type=int, default=600)
    ap.add_argument("--ssh-timeout", type=int, default=120)
    args = ap.parse_args(argv)

    host = load_host(args.host_alias)
    remote = Remote(host)
    cli = remote.expand(LEGACY_CLI)          # the branch CLI (both backends)
    adb = remote.expand(REMOTE_ADB)
    base = remote.expand(f"{REMOTE_BASE}/phase5-fidelity")
    traces_dir = Path(args.traces_dir)
    traces_dir.mkdir(parents=True, exist_ok=True)

    def log(m): print(m, flush=True)

    flow_local = Path(args.flow).resolve()
    flow_rel = flow_local.name
    log(f"[p5] host={host['host']} serial={args.serial} flow={flow_rel} appid={args.appid}")

    remote.sh(f"mkdir -p {shlex.quote(base)}", timeout=args.ssh_timeout)
    remote.put(flow_local, f"{base}/{flow_rel}", timeout=args.ssh_timeout)

    for out, devicecore in SIDES:
        run_side(remote, cli, args.appid, flow_rel, args.serial, adb, base, args.run_timeout,
                 devicecore, out, traces_dir, args.scp_timeout, args.ssh_timeout, log)

    remote.sh(f"rm -rf {shlex.quote(base)}", timeout=args.ssh_timeout, check=False)

    legacy_trace = traces_dir / "legacy" / "steps.jsonl"
    dc_trace = traces_dir / "devicecore" / "steps.jsonl"
    if not legacy_trace.exists() or not dc_trace.exists():
        log(f"[p5] MISSING TRACE: legacy={legacy_trace.exists()} devicecore={dc_trace.exists()} — see logs in {traces_dir}")
        return 2

    report = fidelity_report(legacy_trace, dc_trace, args.tol, flow_rel)
    (traces_dir / "fidelity-report.json").write_text(json.dumps(report, indent=2))

    print("\n" + "=" * 60)
    print(f"FLOW: {report['flow']}")
    print(f"device-core ran {report['deviceCoreSteps']}/{report['totalLegacySteps']} steps  "
          f"served={report['served']} (agree={report['agree']} diverge={report['diverge']})  "
          f"owed(coverage-gap)={report['owedCoverageGaps']}  missing(not-reached)={report['missing']}")
    print(f"FIDELITY: {'GREEN — device-core agrees with maestro on every served step' if report['fidelityGreen'] else 'RED — see divergences'}")
    print("-" * 60)
    for s in report["steps"]:
        print(f"  {s['stepIndex']:>2} {s['status']:<8} {s['command']:<26} legacy={s['legacyVerdict']} devicecore={s['deviceCoreVerdict']}")
    print("=" * 60)
    print(f"report: {traces_dir / 'fidelity-report.json'}")
    return 0 if report["fidelityGreen"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
