# run_differential.py — on-device evidence (Phase 2)

Proof that the platform-general differential harness boots the exact device per replay
folder (no `--serial`), runs legacy vs device-core on that one device, records video for
both, and emits per-step diffs — for both an Android and an iOS replay folder.

## The run

One invocation, both folders, local executor, on this Mac (Xcode + iOS 18.2 sim; Android
SDK + `pixel_6` AVDs):

```bash
python3 run_differential.py --executor local \
    --cli <branch CLI> --video --device-bin <maestro-device wrapper> \
    ~/maestro-replay-harness/DoorDash-*/run_01ktw6bv2rfgkbckvbeksp6vxh \
    ~/maestro-replay-harness/Airalo-*/run_01kty23z49f4ftx2d5rc7bvc5m
```

Each folder booted a fresh device matching its `metadata.json` `device_spec` via the
`maestro-device` wrapper — Android `pixel_6`/`android-34`, iOS `iPhone-16-Pro`/`iOS-18-2` —
captured the serial/udid it assigned, ran legacy then device-core on that one device
(`pm clear` / `simctl terminate` between), recorded `screen.mp4` per backend at the device
layer, pulled each `steps.jsonl`, and diffed them.

## Result — `out/report.json`

`totalFolders=2, ok=2, incomplete=0, errors=0`.

| Folder | platform | served | agree | diverge | owed | missing | video (legacy, device-core) | fidelityGreen |
|---|---|---|---|---|---|---|---|
| Airalo `run_01kty23z…` | iOS | 22 | 20 | 2 | 3 | 16 | ✓ (64 MB), ✓ (2.9 MB) | false |
| DoorDash `run_01ktw6bv…` | Android | 25 | 22 | 3 | 3 | 27 | ✓, ✓ | false |

Each folder produced:

```
out/<runId>/legacy/{steps.jsonl, screen.mp4}
out/<runId>/devicecore/{steps.jsonl, screen.mp4}
out/<runId>/diff.json      # per-step AGREE / DIVERGE / OWED / MISSING vs legacy
out/report.json            # the aggregate above
```

## What `fidelityGreen=false` means here

It's the honest signal, not a harness failure. device-core serves a thin verb set today —
`launchApp` + `assertVisible`/`assertNotVisible`, plus `tapOn(id)` on Android — and declines
the state-changing verbs (inputText, scroll, tap-by-text, setLocation, …) these real customer
flows depend on. So device-core agrees with legacy through the JS/config/setup steps
(`agree` = 20 and 22), then diverges or owes on the device verbs it can't serve, and once it
can't drive the app forward the remaining steps are unreached (`missing`). The harness reports
exactly where device-core does and doesn't match legacy, per step. As device-core gains verbs,
`owed`/`missing` shrink and `agree` grows — the framework is unchanged.

Two known, honest gaps this surfaces (device-core capabilities, not harness bugs): device-core
`launchApp` has no foreground-settle, and device-core's iOS `inspect()` does not yet emit a
visibility signal (`actionability.visible.source=UNAVAILABLE`) — so an iOS `assertVisible` on a
resolved element reports OWED/ERROR rather than a verdict.

## Notes

- No stock backend anywhere — legacy vs device-core only, the same branch CLI ± `MAESTRO_DEVICECORE_ASSERT=1`.
- One device per folder, shared by both backends (never one device per backend — that would inject
  device-to-device variance into the diff).
- iOS between-backend reset is `simctl terminate` only (not a full data wipe); see the "Known
  limitations" note in `RUN_DIFFERENTIAL.md`.
- Remote executor (`--executor remote`) shares the same code path; local is validated here per the
  Phase-2 requirement (at least local). The Android `adb install` disambiguation for device-core
  uses `ANDROID_SERIAL`, so it holds on a multi-device host too.
