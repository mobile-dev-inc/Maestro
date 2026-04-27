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
- The workflow's `workflow_dispatch` inputs `android_os_image` / `android_platform` must exist on the branch. If `android-version-launch` is merged into main, branching off main is fine. If not, branch off `origin/android-version-launch` instead.
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

If `assemble` itself fails (deprecated APIs, dependency mismatch on the new SDK): that's a real Maestro gap — fix it as an additional commit before pushing.

## Step 3: push draft PR + dispatch

```bash
git push -u origin bump-android-api-<old>-<new>
gh pr create --draft \
  --title "chore(android): bump compile/target SDK to <new>" \
  --body "Validates Maestro against API <new>. Driver APKs rebuilt. Test-e2e dispatched with system-images;android-<new>;google_apis;x86_64."

gh workflow run test-e2e.yaml --ref bump-android-api-<old>-<new> \
  -f android_os_image="system-images;android-<new>;google_apis;x86_64" \
  -f android_platform="platforms;android-<new>"

# capture the run id
sleep 3
gh run list --workflow test-e2e.yaml --branch bump-android-api-<old>-<new> --limit 1 --json databaseId,status
```

Always pass *both* `-f` flags. Defaults still point at the old API.

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

1. For each root cause in its report, present the proposed diff to the user (file path + one-line summary + diff + side effects).
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
    -f android_os_image="system-images;android-<new>;google_apis;x86_64" \
    -f android_platform="platforms;android-<new>"
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
- **Dispatching with default inputs** — hits the old API. Always pass both `-f` flags.
- **Auto-applying Maestro source fixes without consent** — every Kotlin patch needs explicit user approval first.
- **Treating `failing/` artifacts as regressions** — that suite is expected to fail.
- **Committing on `main`** — always work on the bump branch. Verify with `git status` before every commit.
- **Skipping the screenshot read** — the screenshot tells you what `maestro.log` and the JSON cannot. It's the highest-signal artifact. Always read it.
