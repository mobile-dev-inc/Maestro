---
name: remote-differential-batch
description: Use when running run_differential.py (the 2.x-vs-3.x device-core fidelity diff)
  across the whole customer-flow corpus on the shared macStadium research pool — fanning the
  batch_differential.py build/partition/dispatch/collect over the iOS/Android hosts via sshpass
  and pulling the per-flow divergences back for triage. Covers the smoke-test go/no-go, the
  host-claim gate, tar-pull hygiene, and the triage handoff.
---

# Batch the fidelity differential across the research pool

Wraps `validation-harness/batch_differential.py`: build the three artifacts locally, partition
the corpus across named hosts, `dispatch` a detached run per host over SSH, `collect` the diffs
back. The script does the mechanics; this skill carries the five invariants it can't enforce and
the operator flow. **Violating the letter of these red-lines is violating the spirit** — a
"faster" shortcut that skips one manufactures false findings or truncates real ones.

## The CLI (source of truth: the docstring, not `RUN_DIFFERENTIAL.md`)

Top-level `--work-dir DIR` (default `batch-out`) holds every artifact below; it precedes the
subcommand.

| Subcommand | Key flags | Writes to work-dir |
|---|---|---|
| `build` | `--device-dir --cli-2x-dir --cli-3x-dir` (all default to the worktree paths) | `build-manifest.json` |
| `partition` | `--ios-hosts a,b` `--android-hosts c,d` `--inventory` + positional folder globs | `partition.json` |
| `dispatch` | `--inventory --remote-root` `--smoke` | `dispatch-state.json` |
| `collect` | `--inventory` | `corpus-report.json`, `triage-folders.txt`, `<host>/out/` |

`dispatch` is **detached** (`nohup … &`, touches `out/DONE` on exit) — it returns immediately;
the run far outlives any single tool wall-clock. There is no `poll` subcommand: check the
`out/DONE` sentinel yourself before `collect` (snippet below).

## Invariant 1 — devices boot ONLY through `maestro-device` (RED-LINE)

The run boots every device via `run_differential.py --device-bin .../maestro-device`, which calls
`maestro-device launch <platform> --os --model` — the production configurator: ConfigStep
pipeline, golden-image bake, production launch path (READY names the golden, e.g.
`PIXEL_6_API_34_v6`). `batch_differential.py` ships and wires this binary for you; do not touch it.

**NEVER substitute the device-hosts research launchers `launch-avd.sh` / `launch-sim.sh`.** They
make `research_spike_*` devices with a different pinned config and **no golden bake**. Booting the
corpus on them injects device-substrate divergence into a diff meant to isolate device-core —
every finding it produces is false. From the device-hosts skill we reuse *only* the non-device
mechanics (inventory parse, pool-claim, tar-pull). Not its booting.

## Invariant 2 — the human names the hosts; the script never self-selects

Hosts come from the device-hosts **pool protocol**: a human is assigned hosts at the gate and
names them. You pass those names; the script validates them and stops there.

- The human names the iOS (`arm-m4s-*`) and Android (`arm-m2m-*`) hosts allocated to them.
  `partition` calls `inventory.validate_named_hosts`, which checks every name against
  `ios_agents` / `android_agents` in `testing.yml` and raises on an unknown one.
- **The count is not fixed.** Today's `testing.yml` lists two of each (iOS `arm-m4s-239` /
  `arm-m4s-241`, Android `arm-m2m-005` / `arm-m2m-007`), but the mechanism is count-agnostic —
  pass however many you were assigned. Never hardcode three.
- **Claim check before touching a host.** `dispatch` runs `claim_probe_script` per host: Android
  `adb devices` + `pgrep 'qemu-system|emulator|maestro'`, iOS `simctl list devices booted` +
  `pgrep`. A non-idle host returns `status: "skipped-busy"` and is reported — **never swapped for
  another**. If you're short a host, go back to the human, don't self-select.
- Credentials (`ansible_host/user/password`) are parsed from the inventory at run time and handed
  straight to `sshpass` via `$SSHPASS`. Never write them down, never echo them.

## Invariant 3 — smoke-test gate first, always

Run `dispatch --smoke` before any full fan-out. It picks **one iOS host + one Android host, one
folder each, then STOPS** (`smoke_selection`). This is the go/no-go for the whole corpus.

**Green smoke** (both platforms) means, per host:
- `maestro-device` bakes-or-clones a golden and boots it (READY names the golden).
- both CLIs (`--cli-2x`, `--cli-3x`) run and emit a step trace.
- `diff.json` has the expected shape (per-step `AGREE`/`DIVERGE`/`OWED`/`WALL_PROPAGATED`/
  `NOT_REACHED`) and pulls back intact via `collect`.
- host env matches the assumptions baked into `run_differential.py`: `JAVA_HOME=/opt/homebrew/
  opt/openjdk@17`, Android SDK at `~/Library/Android/sdk`, `python3 >= 3.10`.

**Red modes and what they mean** — inspect `<host>/out/run.log` and `<host>/out/<runId>/diff.json`:
- host env drift (wrong openjdk, missing SDK, old python3) → fails fast in `run.log`; fix the host
  or drop it, don't fan out onto it.
- golden never boots / bake hangs → device-substrate or first-bake (~10 min Android) issue; iOS
  golden path is the less-exercised one, which is *why* smoke covers an iOS host explicitly.
- a CLI emits no trace → a build/wiring problem in the shipped artifact, not a real divergence.
- `diff.json` missing or malformed shape → the pipeline didn't produce comparable traces; a
  divergence number off this is worthless.

Only after **both** smokes look right do you run the full `dispatch`. Same `partition.json`, drop
`--smoke`.

## Invariant 4 — collect via the counted tar-pull, never `scp -r`

Always `collect`. It runs `remote.pull_out_counted`: `find … | wc -l` on the remote, stream a
`tar -cf -` over SSH into a local extract, recount, and `verify_pull_counts` fails the collect on
any mismatch. **`scp -r` over `sshpass` silently truncates** — a partial pull looks like a
successful one. A count mismatch means re-pull; never aggregate a partial set. The pull can exceed
a single tool wall-clock — run `collect` **detached** and wait on it (walkthrough below).

## Invariant 5 — hand only `diverge > 0` folders to triage

`collect` merges the per-host `report.json`s into `corpus-report.json` and writes
`triage-folders.txt` = the `runId`s where `diff.json` shows `diverge > 0`. Hand **only those** to
the `triage-3x-divergence` skill.

- **Walls are expected.** Every flow walls at some `NotImplemented` verb (`OWED` /
  `WALL_PROPAGATED`); that's recorded, not triaged. The signal is divergence *before* the wall.
- Clean agreement is recorded, not triaged.
- **Golden caches stay warm between runs** — that's the "runs many times" win. Only per-run
  scratch (corpus, artifacts, `out/`) is cleaned; never tear the golden cache down.

## Operator walkthrough (copy-adapt the host names)

You were assigned, say, iOS `arm-m4s-239,arm-m4s-241` and Android `arm-m2m-005,arm-m2m-007`.

```bash
cd ~/codes/worktrees/Maestro/devicecore-integration/validation-harness

# 1. Build the three artifacts locally (device configurator + 2x oracle + 3x candidate).
python3 batch_differential.py build

# 2. Partition the corpus across YOUR named hosts (validates the names against the inventory).
python3 batch_differential.py partition \
  --ios-hosts arm-m4s-239,arm-m4s-241 \
  --android-hosts arm-m2m-005,arm-m2m-007 \
  ~/maestro-replay-harness/*/run_*

# 3. SMOKE GATE — one iOS + one Android, one folder each, then STOP.
python3 batch_differential.py dispatch --smoke

# 4. Wait for both DONE sentinels, then collect just the smoke pair.
#    (Poll snippet below; collect only pulls hosts marked "running".)
python3 batch_differential.py collect

# 5. INSPECT the two diffs before going wider. Confirm golden booted, both traces present,
#    diff.json shape is right, env matched (grep run.log for the openjdk@17 / SDK / python3 lines).
cat batch-out/*/out/run.log
python3 -m json.tool batch-out/*/out/*/diff.json | less

# 6. Only if BOTH smokes are green: full fan-out (same partition.json, no --smoke).
python3 batch_differential.py dispatch

# 7. Wait for all DONE, then collect DETACHED (the tar-pull can exceed a tool wall-clock).
nohup python3 batch_differential.py collect > batch-out/collect.log 2>&1 &

# 8. Hand ONLY the diverging folders to triage-3x-divergence.
cat batch-out/triage-folders.txt
```

Poll the `out/DONE` sentinel per host (no `poll` subcommand — use the transport helpers):

```bash
python3 - <<'PY'
import json, os, inventory, remote
INV = os.path.expanduser("~/codes/copilot/didb/infrastructure/macstadium/inventory/testing.yml")
state = json.load(open("batch-out/dispatch-state.json"))
inv = open(INV).read()  # the same --inventory you passed to dispatch
for h in state["hosts"]:
    if h["status"] != "running":
        print(h["host"], h["status"]); continue
    creds = inventory.parse_host_creds(inv, h["host"])
    done = remote.poll_done(creds, f"{h['remote_dir']}/out/DONE")
    print(h["host"], "DONE" if done else "running")
PY
```

## Copy-adapt provenance (for maintainers)

`validation-harness/remote.py` is **copied and adapted** — not imported, not vendored — from the
device-hosts `run_remote.sh` (the 92-line verified-tar-pull version at
`~/codes/blind-hid/.claude/skills/run-spike-experiment/reference/run_remote.sh`), per that skill's
"copy and adapt, not run in place" rule. `inventory.py` ports its run-time credential-parse
heredoc into a tested function. If the transport needs changing, read that upstream reference —
don't diverge silently from it.

## Red flags — STOP

- Reaching for `launch-avd.sh` / `launch-sim.sh` "just to boot faster" → false divergence. Use
  `maestro-device`.
- Picking a replacement host because one was busy → the human assigns hosts. Report skipped-busy.
- Hardcoding three hosts → the pool size varies. Pass what you were assigned.
- Skipping `--smoke` because "it worked last time" → env drifts; the golden path breaks. Smoke first.
- `scp -r` instead of `collect` → silent truncation. Always the counted tar-pull.
- Triaging a walled or agreeing folder → only `diverge > 0` goes to `triage-3x-divergence`.
- Reading a divergence number off a run whose `steps.jsonl`/`diff.json` is empty or malformed →
  fix the pipeline, re-run, then read.
