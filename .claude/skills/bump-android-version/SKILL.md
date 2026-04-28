---
name: bump-android-version
description: Use when bumping Maestro's Android compileSdk/targetSdk to a new API level and validating end-to-end against the test-e2e GHA workflow until the test-android job is green.
---

# Bump Android Version

## Overview

Drive an Android-version bump in Maestro commit-by-commit on a single feature branch: edit gradle, rebuild the on-device driver APKs, push a draft PR, dispatch `test-e2e.yaml` against the new system image, watch the `test-android` job, and diagnose+fix any newly-failing `passing/` flows. Loop until green.

## When to Use

- User asks to bump Maestro's Android `compileSdk` / `targetSdk` (e.g. "bump to API 36").
- User wants to validate Maestro against a new Android system image.

One commit per logical step. Don't bundle the gradle bump and the APK rebuild — they need to be separately revertible. After a failing run, fixes are committed and the workflow re-dispatched. Loop until green; do not stop on the first re-dispatch.

## Pre-flight

- Working tree clean (`git status`).
- `main` up to date: `git fetch origin && git checkout main && git pull --ff-only origin main`.
- The workflow's `workflow_dispatch` inputs `android_version` (and optional `app` / `flow`) must exist on the branch. 
- `android_version` is a `choice` input — its options live in `.github/workflows/test-e2e.yaml`. If `<new>` isn't already listed (e.g. bumping to API 37), add it to the `options:` list in a separate commit before dispatching, otherwise the workflow rejects the input.
- The `validate-inputs` job rejects `android_version <= android-29`. Bumps below API 29 aren't supported.
- Branch name: `bump-android-api-<old>-<new>` (e.g. `bump-android-api-34-36`).

```bash
git checkout -b bump-android-api-<old>-<new> origin/main
```

## Commit 1: bump compile/target SDK

Read current values from `maestro-android/build.gradle.kts` (`compileSdk` and `targetSdk`). Tell the user the current values verbatim, then ask for the new target:

> Current `compileSdk` / `targetSdk` = `<N>`. What's the new API level?

Edit both lines. Also `rg -n "compileSdk\s*=|targetSdk\s*=" --type kotlin --type-add 'kotlin:*.kts'` to catch consistent occurrences in other modules — only update those that intentionally track the same SDK. Commit:

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
  --body "Validates Maestro against API <new>. Driver APKs rebuilt. Test-e2e dispatched with system-images;android-<new>;google_apis;x86_64."

gh workflow run test-e2e.yaml --ref bump-android-api-<old>-<new> \
  -f android_version="android-<new>"

# capture the run id
sleep 3
gh run list --workflow test-e2e.yaml --branch bump-android-api-<old>-<new> --limit 1 --json databaseId,status
```

Always pass `-f android_version=android-<new>` — the default is `android-32` and would silently re-test the old API. The workflow constructs the full `system-images;...` string internally. `app` and `flow` are optional narrowing knobs (defaults: `demo_app`, all flows); leave them off for the full bump-validation run.

## Step 4: watch the test-android job

```bash
gh run watch <run_id>
```

Block in the foreground; the run is the bottleneck. On green: update PR from draft → ready, done.

On red, download artifacts:

```bash
gh run download <run_id> --name maestro-root-dir-android -D /tmp/maestro-android-<run_id>
```

## Step 5: diagnose (delegated to subagent)

Dispatch the **`diagnose-maestro-failure`** subagent with the artifact directory `/tmp/maestro-android-<run_id>` (or the GHA run URL — the subagent handles both). It diagnoses each passing-suite failure in isolation and returns a structured report grouped by root cause (cascades collapsed). It does **not** apply fixes — that's your job here.

When the subagent returns:

**Fixes go in Maestro source — not in `.github/workflows/test-e2e.yaml`.** A failing flow on a new API level reflects a behaviour Maestro users will also hit on their own machines/CI, not just ours. Patching the GHA workflow (e.g. extra `adb shell settings put …`, command-line tweaks, AVD setup steps) hides the regression from real users and ships a Maestro that's only green inside our CI. Acceptable fix targets:

- `maestro-android/**` — on-device driver Kotlin (rebuild driver APKs after editing).
- `maestro-client/**`, `maestro-orchestra/**`, etc. — host-side Kotlin.
- `e2e/demo_app/**` — the demo_app fixture's Android manifest, `build.gradle.kts`, or `.maestro/` flow YAML (only when the failure is a fixture issue, not a driver issue).

Valid edits to `test-e2e.yaml` during this loop are workflow-shape changes (matrix, retention, dispatch inputs) — and the narrow exception below. Anything else (e.g. extra `adb shell settings put …` to mask a Maestro driver gap) is off-limits. If the subagent proposes a workflow patch, push back: ask it (or yourself) where the same fix would live in `maestro-android/` or `maestro-client/` so users on the new API level inherit it automatically.

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

## Termination

Stop when `test-android` is green on the latest run. Then:

```bash
gh pr ready <pr_number>
```

If the loop has gone three iterations without progress (same flow keeps failing for different reasons after fixes), pause and ask the user how to proceed — don't spin indefinitely.

## Anti-patterns

- **Bundling gradle + APK rebuild into one commit** — kills bisectability. Always two commits.
- **Dispatching with default inputs** — `android_version` defaults to `android-32` and hits the old API. Always pass `-f android_version=android-<new>`.
- **Forgetting to extend the `android_version` choice enum** — the workflow rejects API levels not listed in `options:`. Add `<new>` to the enum in a separate commit before the first dispatch.
- **Auto-applying Maestro source fixes without consent** — every Kotlin patch needs explicit user approval first.
- **Patching `.github/workflows/test-e2e.yaml` to mask a driver behaviour gap** — workflow band-aids hide the regression from users running Maestro outside our CI. Fix `maestro-android/` or `e2e/demo_app/` instead so the fix ships with the driver APKs.
- **Treating `failing/` artifacts as regressions** — that suite is expected to fail.
- **Committing on `main`** — always work on the bump branch. Verify with `git status` before every commit.
- **Skipping the screenshot read** — the screenshot tells you what `maestro.log` and the JSON cannot. It's the highest-signal artifact. Always read it.
