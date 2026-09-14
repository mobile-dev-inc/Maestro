---
name: bump-android-version
description: Use when bumping Maestro's Android compileSdk/targetSdk to a new API level and validating end-to-end against the test-e2e GHA workflow until the test-android job is green.
---

# Bump Android Version

## Overview

Drive an Android-version bump in Maestro commit-by-commit on a single feature branch: edit gradle, rebuild the on-device driver APKs, push a draft PR, dispatch `test-e2e.yaml` against the new system image, watch the `test-android` job, and diagnose+fix any newly-failing `passing/` flows. Loop until green. Then advance the driver's declared API range, let the PR's `test-start-device` check boot the new API through the CLI, and finish by writing the rollup into the PR body.

## When to Use

- User asks to bump Maestro's Android `compileSdk` / `targetSdk` (e.g. "bump to API 36").
- User wants to validate Maestro against a new Android system image.

One commit per logical step. Don't bundle the gradle bump and the APK rebuild — they need to be separately revertible. After a failing run, fixes are committed and the workflow re-dispatched. Loop until green; do not stop on the first re-dispatch.

**The loop only acts on `passing/` failures.** `tests/demo_app/passing/` is the regression-detection suite — its flows are expected to pass, so a failure there is a real signal. `tests/demo_app/failing/` is the negative-path suite (its flows are *expected* to fail) and is ignored end-to-end. The diagnose agent rejects `failing/` artifacts and rejects retry-recovered flows in `passing/` (see [`.claude/agents/diagnose-maestro-failure.md`](../../agents/diagnose-maestro-failure.md)). If the run is otherwise green except for `failing/`, that's a green run.

## The declared range

`maestro-android/supported-apis.properties` (`min=`, `max=`, `device=`) is the driver's claim of which API levels it supports, and the device model CI proves them on. Two things read it:

- `test-e2e.yaml`'s `test-start-device` job boots `max` on `device` on every PR through `maestro start-device` (the CLI's own provisioning path, which `test-android` never exercises), plus every level the PR adds to the range; a `device` change or a manual dispatch boots the whole range. It fails the PR if a cell cannot be created or booted. It is part of `e2e-gate`.
- The copilot device-readiness harness, through the `maestro` submodule: the device catalog may not list an OS above `max`.

`compileSdk` moves first (Commit 1). `max` moves last (Commit 3), only once the corpus is green at the new API. The job also asserts `max <= compileSdk`, so a range advanced ahead of the SDK bump is red on its own.

## Pre-flight

- Working tree clean (`git status`).
- `main` up to date: `git fetch origin && git checkout main && git pull --ff-only origin main`.
- The workflow's `workflow_dispatch` inputs `android_version` (and optional `app` / `flow`) must exist on the branch.
- `android_version` is a free-form `android-<N>` or `android-<N>.<M>` string. `validate-inputs` rejects anything below `min` in `supported-apis.properties` and enforces no ceiling, so `android-<new>` dispatches without touching the workflow. There is no enum to extend.
- From API 37 Google ships minor platform versions (`android-37.1`) and sometimes only a variant image tag (`google_apis_ps16k`). Both jobs derive `system-images;<version>;google_apis;x86_64`; if the new API has no such image, the derivation in `test-e2e.yaml` (test-android's `ANDROID_OS_IMAGE`, test-start-device's pre-install and `--device-os`) needs a rule for that level. That is a workflow-shape edit in its own `ci(e2e):` commit before the first dispatch.
- Read the current declared range: `cat maestro-android/supported-apis.properties`. `max` should equal the current `compileSdk`; if it doesn't, tell the user before starting.
- Branch name: `bump-android-api-<old>-<new>` (e.g. `bump-android-api-34-36`).

```bash
git checkout -b bump-android-api-<old>-<new> origin/main
```

## Commit 1: bump compile/target SDK

Read current values from `maestro-android/build.gradle.kts` (`compileSdk` and `targetSdk`). Tell the user the current values verbatim, then ask for the new target:

> Current `compileSdk` / `targetSdk` = `<N>`. What's the new API level?

Edit both lines. Also `rg -n "compileSdk\s*=|targetSdk\s*=" --type kotlin --type-add 'kotlin:*.kts'` to catch consistent occurrences in other modules — only update those that intentionally track the same SDK. Leave `supported-apis.properties` alone here; it moves in Commit 3. Commit:

```bash
git add maestro-android/build.gradle.kts <other touched files>
git commit -m "chore(android): bump compile/target SDK from <old> to <new>"
```

## Commit 2: rebuild driver APKs

```bash
./gradlew :maestro-android:assemble :maestro-android:assembleAndroidTest
```

The build's `copyMaestroAndroid` / `copyMaestroServer` finalizers update three checked-in files. Stage and commit *only* those:

```bash
git add maestro-client/src/main/resources/maestro-app.apk \
        maestro-client/src/main/resources/maestro-server.apk \
        maestro-client/src/main/resources/maestro-android-source.sha256
git commit -m "chore(android): rebuild driver APKs against API <new>"
```

If `assemble` fails, **stop and surface the failure to the user** with the gradle error and a suggested fix. Do not proceed without rebuilt driver APKs. Once we have assemble fixed and new drivers are commited, we can proceed to the next step.

## Step 3: push draft PR + dispatch

```bash
git push -u origin bump-android-api-<old>-<new>
gh pr create --draft \
  --title "chore(android): bump compile/target SDK to <new>" \
  --body "Validates Maestro against API <new>. Driver APKs rebuilt. Test-e2e dispatched with system-images;android-<new>;google_apis;x86_64. Rollup follows once green."

gh workflow run test-e2e.yaml --ref bump-android-api-<old>-<new> \
  -f android_version="android-<new>"

# capture the run id
sleep 3
gh run list --workflow test-e2e.yaml --branch bump-android-api-<old>-<new> --limit 1 --json databaseId,status
```

Always pass `-f android_version=android-<new>` — the default is `android-32` and would silently re-test the old API. The workflow constructs the full `system-images;...` string internally. `app` and `flow` are optional narrowing knobs (defaults: `demo_app`, all flows); leave them off for the full bump-validation run.

The push also starts the PR's own `pull_request` run. Its `test-start-device` job boots the *declared* `max`, which is still `<old>` until Commit 3, so it says nothing about `<new>` yet. Note its run id anyway; the rollup links it.

## Step 4: watch the test-android job

```bash
gh run watch <run_id>
```

Block in the foreground; the run is the bottleneck. **Green here means every `passing/` flow passed (terminally — retry-recovered counts as passed). `failing/` outcomes do not affect the verdict.** On green: go to **Commit 3**.

On red, download artifacts:

```bash
gh run download <run_id> --name maestro-root-dir-android -D /tmp/maestro-android-<run_id>
```

## Step 5: diagnose (delegated to subagent)

**Rig check first.** Some failures on a new API are the runner's tooling, not the driver, and a driver patch would only hide them. Before dispatching the diagnose agent, open the `Create AVD` step log of the red run and read the `avdmanager version + AVD target` block:

- `target=android-0` there, or `avdmanager` rejecting the device profile, means the runner's cmdline-tools do not know the new image. Typical downstream symptoms: the guest aborts at boot (surfaceflinger / gfxstream in logcat), or many `passing/` flows fail with adb "Broken pipe" right after boot.
- Fix: bump `ANDROID_CMDLINE_TOOLS_ZIP` at the top of `test-e2e.yaml` to a current build, in its own commit, then re-dispatch and only diagnose what is still red on the newer tools:
  ```bash
  git commit -m "ci(e2e): bump cmdline-tools for API <new> images"
  ```
- A driver fix that proposes to retry or wait around boot-time instability is the signal to run this check, not to apply the fix.

Dispatch the **`diagnose-maestro-failure`** subagent with the artifact directory `/tmp/maestro-android-<run_id>` (or the GHA run URL — the subagent handles both). It diagnoses each passing-suite failure in isolation and returns a structured report grouped by root cause (cascades collapsed). It does **not** apply fixes — that's your job here.

When the subagent returns:

**Fixes go in Maestro source — not in `.github/workflows/test-e2e.yaml`.** A failing flow on a new API level reflects a behaviour Maestro users will also hit on their own machines/CI, not just ours. Patching the GHA workflow (e.g. extra `adb shell settings put …`, command-line tweaks, AVD setup steps) hides the regression from real users and ships a Maestro that's only green inside our CI.

Acceptable fix targets and their roles are documented in [`AGENTS.md`](../../../AGENTS.md) (modules `maestro-android/`, `maestro-client/`, `maestro-orchestra/`, `e2e/demo_app/`). Driver-behaviour fixes belong in `maestro-android/` or `maestro-client/`; fixture-only issues belong in `e2e/demo_app/`. Editing the driver source means rebuilding the driver APKs (`./gradlew :maestro-android:assemble :maestro-android:assembleAndroidTest`) before re-dispatching.

Valid edits to `test-e2e.yaml` during this loop are workflow-shape changes (matrix, retention, dispatch inputs), the test-rig cmdline-tools pin (`ANDROID_CMDLINE_TOOLS_ZIP` at the top of the workflow, when the new image or a device profile needs a newer `sdkmanager`/`avdmanager` on the runner) — and the narrow exception below. That pin is runner tooling, not a fleet pin; the fleet's toolchain pins live in maestro-device. Anything else (e.g. extra `adb shell settings put …` to mask a Maestro driver gap) is off-limits. If the subagent proposes a workflow patch, push back: ask it (or yourself) where the same fix would live in `maestro-android/` or `maestro-client/` so users on the new API level inherit it automatically.

**Exception — third-party app first-run UI not triggered by a Maestro API.** A pre-installed app's own onboarding (Chrome Welcome / "Make Chrome your own", browser default-app picker, Play Protect prompts that fire at app launch) is environment-harness state, not driver behaviour. Maestro doesn't expose a public API that triggers them — they're side effects of the OS image we picked. These may be pre-disabled in the AVD setup step (typical knobs: `setprop debug.chrome.command_line`, `settings put global …` flags). Test: *if Maestro called nothing related to this dialog, would it still appear?* If yes → CI workaround is fair game. If no (e.g. `setLocation` triggers GMS Location Accuracy via `FusedLocationProviderClient` inside the driver) → fix the driver instead.

1. For each root cause in its report, present the proposed diff to the user (file path + one-line summary + diff + side effects). Reject any proposal that targets `test-e2e.yaml` for driver-behaviour reasons before showing it — re-scope to Maestro source first.
2. **Ask consent explicitly per fix:**
   > Proposed fix for `<root cause>`: `<one-liner>`. Apply?
3. On approval: edit, commit with a focused message — don't bundle unrelated fixes.
   ```bash
   git commit -m "fix(android-<new>): <summary>"
   ```
4. On rejection: skip that fix; record what was rejected so you don't re-propose it next iteration.

After all approved fixes for this iteration are committed:

- **If any fix was applied:** push and re-dispatch, then loop back to **Step 4** with the new `run_id`.

  ```bash
  git push
  gh workflow run test-e2e.yaml --ref bump-android-api-<old>-<new> \
    -f android_version="android-<new>"
  ```

- **If no fixes were applied** (everything was out-of-scope, or the user rejected every proposal): pause and ask the user how to proceed. Do not keep dispatching.

Keep a running list per iteration: run id, which `passing/` flows went red, root cause, fix commit (or "rejected"). The rollup in Step 7 is written from it.

## Commit 3: advance the declared range

Only after Step 4 is green at `android-<new>`. Edit `max=` in `maestro-android/supported-apis.properties` to `<new>` (leave `min=` alone) and commit it on its own. `max` may carry the minor platform version the image actually has (`max=37.1`); its integer part is the API level:

```bash
git add maestro-android/supported-apis.properties
git commit -m "chore(android): declare API <new> supported"
git push
```

This is the commit the copilot harness reads through the submodule; nothing above `max` can enter the device catalog, so it must not land ahead of the green run.

## Step 6: watch the start-device check

The push starts a new `pull_request` run of `test-e2e.yaml`. Its `test-start-device` job now has an `android-<new>` cell on the declared device.

```bash
gh pr checks <pr_number> --watch
```

Green: go to Step 7. Red on the `Start device on Android (android-<new>)` check: read the job log (`gh run view <run_id> --log-failed`). Two causes, two fix targets:

- **The CLI cannot provision the image** — `DeviceSpec` derives a package that does not exist (minor-versioned platforms like `android-37.1`, variant tags like `google_apis_ps16k`), `osVersion` parses to 0, `avdmanager` gets the wrong arguments. Fix in `maestro-client/.../device/DeviceSpec.kt`, `DeviceService.kt` or `maestro-cli/.../device/DeviceCreateUtil.kt`, with a unit test, one concern per commit, consent per fix as in Step 5:
  ```bash
  git commit -m "feat(device): support API <new> in start-device"
  ```
- **The runner's cmdline-tools cannot serve the image or the device profile** — `avdmanager` writes `target=android-0`, or `No device found matching --device <model>`. Bump `ANDROID_CMDLINE_TOOLS_ZIP` in `test-e2e.yaml` (allowed: it is test-rig tooling) in its own `ci(e2e):` commit.

Push and watch again. Loop until green. The same three-iterations-without-progress rule as below applies here.

## Step 7: rollup and ready

Write the rollup into the PR body. It is the record reviewers and the next bump read; the PR is not ready without it. Every section is required; write "none" rather than dropping one.

```bash
gh pr edit <pr_number> --body-file rollup.md
gh pr ready <pr_number>
```

`rollup.md`:

```markdown
## API <old> → <new>

**Range:** `supported-apis.properties` max <old> → <new>. compileSdk/targetSdk <old> → <new>.

### Commits
- `<sha>` chore(android): bump compile/target SDK from <old> to <new>
- `<sha>` chore(android): rebuild driver APKs against API <new>
- `<sha>` fix(android-<new>): <summary> — <which passing/ flows it fixed>
- `<sha>` chore(android): declare API <new> supported

### Runs
| Run | Trigger | test-android | test-start-device | Result |
|---|---|---|---|---|
| <link> | dispatch android-<new> | red: <flow>, <flow> | old max | <root cause> |
| <link> | dispatch android-<new> | green | old max | |
| <link> | pull_request after Commit 3 | (android-32 default) | green at <new> | |

### passing/ flows that went red
| Flow | Run | Root cause | Fixed by |
|---|---|---|---|
| <flow> | <link> | <one line> | `<sha>` / rejected: <why> |

### Workflow edits
<`ci(e2e):` commits, if any, and why each is test-rig scope. Or "none".>

### Left open
<anything rejected or deferred, or "none".>
```

## Termination

Stop after Step 7: `test-android` green at `android-<new>` on the latest dispatch, `test-start-device` green at `<new>` on the PR, rollup on the PR, PR ready.

If the loop has gone three iterations without progress (same flow keeps failing for different reasons after fixes), pause and ask the user how to proceed — don't spin indefinitely.

## Anti-patterns

- **Bundling gradle + APK rebuild into one commit** — kills bisectability. Always two commits.
- **Dispatching with default inputs** — `android_version` defaults to `android-32` and hits the old API. Always pass `-f android_version=android-<new>`.
- **Patching the driver around a rig problem** — boot-time aborts and post-boot "Broken pipe" on a new image can be the runner's cmdline-tools writing `target=android-0`. Read the `Create AVD` log and bump `ANDROID_CMDLINE_TOOLS_ZIP` before accepting any retry-shaped driver fix.
- **Advancing `max` before the corpus is green** — the harness downstream trusts that file; it must trail the proof, never lead it. Same for bundling it into Commit 1.
- **Marking the PR ready on a green `test-android` alone** — `test-start-device` at `<new>` is the CLI's proof and only runs after Commit 3. Green e2e with the old `max` proves nothing about provisioning.
- **Skipping the rollup or trimming its sections** — an ad hoc body that names one fix and one run loses the red runs, the per-flow causes and the rejected proposals, which is exactly what the next bump needs.
- **Auto-applying Maestro source fixes without consent** — every Kotlin patch needs explicit user approval first.
- **Patching `.github/workflows/test-e2e.yaml` to mask a driver behaviour gap** — workflow band-aids hide the regression from users running Maestro outside our CI. Fix `maestro-android/` or `e2e/demo_app/` instead so the fix ships with the driver APKs.
- **Treating `ANDROID_CMDLINE_TOOLS_ZIP` as the fleet's pin** — it is the GitHub runner's tooling. The fleet's cmdline-tools, platform-tools and emulator pins live in maestro-device and are bumped by its own skill.
- **Treating `failing/` artifacts as regressions** — that suite is expected to fail.
- **Treating `❌` screenshots in `passing/` as regressions on their own** — when `retryCommand` recovers a failed attempt, Maestro writes only the final `COMPLETED` entry to `commands-(<flow>).json` but leaves the `❌` screenshot from the failed attempt on disk (e.g. `screenshot-❌-<ts>-(retry).png` for the `retry` flow itself). The diagnose agent uses `commands-*.json` containing a terminal `FAILED` entry as the source of truth — orphan `❌` screenshots without a matching FAILED entry are noise.
- **Committing on `main`** — always work on the bump branch. Verify with `git status` before every commit.
- **Skipping the screenshot read** — the screenshot tells you what `maestro.log` and the JSON cannot. It's the highest-signal artifact. Always read it.
