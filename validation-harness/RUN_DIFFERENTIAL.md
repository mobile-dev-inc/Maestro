# run_differential.py — the general legacy vs device-core runner

## What it is

One script that replays a list of replay-harness run folders — legacy vs
device-core, no stock. For EACH folder it boots the exact device the folder
asks for (local or remote, one fresh device per folder, no `--serial`),
installs the app once, then runs BOTH backends on that same device: legacy
(no env var) and device-core (`MAESTRO_DEVICECORE_ASSERT=1`), with a state
reset between them. It records device-layer video (best-effort), pulls each
backend's per-step trace, diffs them through `fidelity.fidelity_report` (see
[Relationship to the other scripts](#relationship-to-the-other-scripts)
below), and writes a per-folder `diff.json` plus an aggregate `report.json`.

One device per folder, shared by both backends, is the whole point: boot
once, teardown once, reset in place between backends. Booting a second
device for the second backend would inject device-to-device variance into a
diff that's supposed to isolate backend variance.

This is legacy vs device-core ONLY — same branch CLI, toggled by
`MAESTRO_DEVICECORE_ASSERT`. There's no stock/quad control here (see
[Relationship to the other scripts](#relationship-to-the-other-scripts)).

The module reports data; it is not a pass/fail gate. A flow FAIL or ERROR is
data, not a harness error — the CLI always runs with `check=False`.

## Input contract

A run folder is read directly — no corpus index. Pass folders (or globs
that expand to folders) as positional args; each must contain:

- `metadata.json` — the single source of truth, read by `run_folder.py`:
  - `package_id`
  - `platform` — `"ANDROID"` or `"IOS"` (case-insensitive)
  - `device_spec` — `{model, os, locale}` (`locale` optional)
  - `env` — dict of `-e KEY=VALUE` pairs passed identically to both backends
  - `flow_file_path` — path to the flow YAML, relative to `workspace/`
  - `run_id` — optional; defaults to the folder's basename
- `workspace/` — staged and untarred onto the executor; the flow file is
  resolved inside it
- `app.apk` (Android) or `app.ipa` (iOS) — the app binary
- `run.sh` — not read by `run_differential.py` itself (folder-contract
  artifact from the replay harness; the script drives the CLI directly)

`expand_folders()` globs each positional arg and keeps only directories that
contain a `metadata.json`, so `DoorDash-*/run_*` on the command line is fine.

## Executor seam

`--executor local` (the only supported value — the remote/`run_gate.py`
executor path has been removed) selects `LocalExecutor`, which implements the
`sh` / `put` / `get` / `boot` / `teardown` interface that `run_one_folder`
drives.

`LocalExecutor` boots the EXACT `device_spec` from the folder through the
`maestro-device` wrapper (`--device-bin`, default `maestro-device` on
`PATH`) — the wrapper owns the device lifecycle end to end (create, boot,
block until signalled, teardown on exit); `run_differential.py` never
reimplements AVD/simulator creation. It launches the wrapper, waits for a
`READY platform=... serial=... ` (Android) or `READY platform=... udid=...`
(iOS) line, and holds the handle needed to tear it down later:

- **Android**: `<device-bin> launch android --os <os> --model <model>`. If
  the folder's `device_spec.locale` is set it's NOT passed (the Android boot
  path has no `--locale`), and `specFidelity` is recorded as `"approx"`. No
  locale set → `"full"`.
- **iOS**: `<device-bin> launch ios --os <os> --model <model> [--locale
  <locale>]`. `specFidelity` is always `"full"` on iOS.

There is no `--serial` flag — the whole point of the executor seam is that
the harness boots its own device per the folder's spec rather than
attaching to an operator-supplied one.

## Backends

`BACKENDS = [("legacy", {}), ("devicecore", {"MAESTRO_DEVICECORE_ASSERT":
"1"})]` — one CLI (`--cli`, required), the same binary for both sides.
Before every backend's run the harness explicitly `unset
MAESTRO_DEVICECORE_ASSERT` and then re-exports it only for the device-core
pass, so an operator who happens to have that var set in their own shell
can't silently corrupt a legacy run. No stock backend is involved.

## The target-UX command

Verified against the actual `argparse` in `main()` — the flags below match
what's implemented (`--executor` local-only, `--cli-2x`, `--cli-3x`,
`--video`, `--device-bin`, `--out`, `--tol`, `--run-timeout`, and the
trailing `folders` positional globs).

```bash
python3 run_differential.py --executor local \
    --cli-2x ~/dir-research-scratch/2x/maestro/bin/maestro \
    --cli-3x ~/dir-research-scratch/3x/maestro/bin/maestro --video \
    --device-bin <maestro-device wrapper> \
    ~/maestro-replay-harness/DoorDash-*/run_* ~/maestro-replay-harness/Airalo-*/run_*
```

Other flags, all optional: `--out` (default `out`), `--tol` (px tolerance
for the coordinate diff, default `2`), `--run-timeout` (per-backend CLI
timeout in seconds, default `900`).

## Output layout

What `run_one_folder` actually writes, per folder, under `--out` (default
`out/`):

```
out/<runId>/legacy/steps.jsonl
out/<runId>/legacy/screen.mp4       (if --video)
out/<runId>/devicecore/steps.jsonl
out/<runId>/devicecore/screen.mp4   (if --video)
out/<runId>/diff.json               # legacy vs device-core per step (fidelity_report shape: AGREE/DIVERGE/OWED/MISSING)
out/report.json                     # aggregate: one summary line per folder + totals
```

`diff.json` is exactly `fidelity.fidelity_report()`'s return value —
`flow`, `reachDepth`, `totalLegacySteps`, `served`, `agree`, `diverge`,
`owed`, `missing`, `fidelityGreen`, the per-step `steps` list
(status ∈ AGREE/DIVERGE/OWED/MISSING), and the raw `diff_traces.diff_flow`
output under `rawDiff`.

`report.json`'s `folders` array carries one entry per input folder:
`runId`, `platform`, `package`, `specFidelity` (`full`|`approx`), `status`
(`ok`|`incomplete`|`error`), `reachDepth` (device-core's step count),
`served`, `agree`, `diverge`, `owed`, `missing`, `fidelityGreen`,
`videoLegacy`/`videoDeviceCore` (whether each video was captured), plus
`totalFolders`/`ok`/`incomplete`/`errors` counts at the top level. A folder
lands in `incomplete` (not `error`) when the boot/install/run sequence
completed but one or both per-step traces couldn't be pulled — an `error`
means an exception was raised before a report could be built at all (e.g. a
malformed `metadata.json` or a boot timeout).

## Honest signal

iOS (and any platform where device-core's surface is thin) will show many
OWED and DIVERGE entries until device-core matures — that's the correct
signal, not a harness failure. Two known, currently-open gaps that show up
this way and are device-core's to fix, not the harness's:

- **`launchApp` foreground-settle** — device-core's `launchApp` reports
  success when the platform launch command returns, not when the app
  reaches the foreground, so an assert immediately after launch can race it.
- **iOS visibility signal** — device-core's iOS actionability/visibility
  surface is thinner than Android's, so iOS folders reach less depth and
  show more OWED coverage gaps at this stage.

Both are exactly the kind of precise, actionable signal this framework
exists to produce.

## Known limitations

- **iOS between-backend reset is not a data wipe.** On Android, `pm clear`
  fully wipes app state/storage between the legacy and device-core passes.
  On iOS there is no `pm clear` equivalent — `reset_cmd` only issues `simctl
  terminate` (kills the running process), so the device-core pass can
  inherit whatever app state the legacy pass left behind. This can inflate
  iOS DIVERGE counts beyond what the backends themselves disagree on. It's
  acceptable for now because iOS device-core currently serves only
  `launchApp` + `assertVisible`/`assertNotVisible(text)`, and the legacy
  trace is always the oracle — but a reader should know iOS diffs are not
  guaranteed to start from a clean slate the way Android's are.
- **Android screenrecord caps at ~3 minutes per segment** — see below.

## Android screenrecord's ~3-minute cap

`device_ops.video_start_cmd` uses `adb shell screenrecord`, which caps a
single recording at roughly 3 minutes. `run_differential.py` records a
single segment per backend per folder (`start_video` / `stop_video_and_pull`
in `device_ops.py`) — there is no multi-segment chunking. A flow that runs
longer than ~3 minutes on a given backend will have its video truncated to
that first segment; the harness does not loop `screenrecord` into numbered
files and concatenate them. This is a real, documented limitation of the
current code, not a silently-ignored one — see the docstring on
`device_ops.video_start_cmd`. iOS (`simctl io recordVideo`) has no
equivalent cap. Video is always best-effort on both platforms: a recording
failure is caught, reflected in `report.json`'s `videoLegacy` /
`videoDeviceCore` flags, and never fails the run.

## Relationship to the other scripts

- **`diff_traces.py` / `classify.py`** — the shared, platform-agnostic
  comparison engine, reused unchanged. `run_differential.py` doesn't call
  `diff_traces` directly; it goes through `fidelity.fidelity_report`, which
  itself calls `diff_traces.load_steps` / `diff_traces.diff_flow` with the
  2.x oracle as `a` and the 3.x candidate as `b`.
- **`fidelity.py`** — where `fidelity_report()` lives (extracted from the
  now-deleted `phase5_fidelity.py`/`run_gate.py` remote/gate machinery;
  `run_differential.py` is local-only). It's the AGREE/DIVERGE/OWED/MISSING
  classification `run_differential.py` reuses rather than reimplementing.

## Tests

```
cd validation-harness && python3 -m pytest -q
```

`test_run_differential.py` drives `run_one_folder` against a `FakeExecutor`
(no real device), locking in the folder → boot → install → per-backend
reset/video/run/pull → diff → report sequence described above.
