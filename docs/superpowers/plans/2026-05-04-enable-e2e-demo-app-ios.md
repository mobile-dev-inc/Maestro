# Enable demo_app E2E on iOS — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop skipping the Flutter `demo_app` workspace on iOS in the E2E test dispatcher, open a draft PR so `test-e2e.yaml` runs the iOS demo_app job, and enter the diagnose-and-decide iteration loop.

**Architecture:** Tiny dispatcher edit. `e2e/run_tests` currently short-circuits demo_app on iOS in two places (single-app mode at line 129, full-suite mode at line 154, plus the `# demo_app has OOM issues on GHA` comment at line 153). Removing those three lines is the entire enablement diff — tag-based filtering on `--exclude-tags android,ios_device_configuration,android_device_configuration,web` already handles platform partitioning of the flow corpus.

**Tech Stack:** Bash (e2e/run_tests), GitHub Actions (.github/workflows/test-e2e.yaml), GitHub CLI (`gh` 2.66.1) for draft PR.

**Spec:** `docs/superpowers/specs/2026-05-04-enable-e2e-demo-app-ios-design.md`

**Repo conventions to honor:**
- Commits in this repo do **not** include the `Co-Authored-By: Claude` trailer.
- The `docs/` directory is intentionally untracked. Never `git add docs/...`. Stage explicit paths only — no `git add -A` / `git add .`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `e2e/run_tests` | Modify (delete 3 lines) | Test dispatcher; stops short-circuiting demo_app on iOS. |

That's all the source-tree work for the enablement step. Subsequent iteration tasks (after the first PR run) will edit individual flow YAML files under `e2e/demo_app/.maestro/...` based on diagnoses, but those edits cannot be predetermined — they're driven by the `diagnose-maestro-failure` agent's per-failure reports.

---

## Task 1: Create the branch

**Files:** none (git only)

- [ ] **Step 1: Confirm starting state**

Run:
```bash
git status --short
git branch --show-current
```
Expected:
- `git status --short` shows only `?? docs/` (untracked spec/plan dir).
- Current branch is `main`.

If anything else is dirty, stop and surface it to the user before proceeding.

- [ ] **Step 2: Create and switch to the feature branch**

Run:
```bash
git checkout -b enable-e2e-demo-app
```
Expected: `Switched to a new branch 'enable-e2e-demo-app'`.

- [ ] **Step 3: Verify branch**

Run:
```bash
git branch --show-current
```
Expected: `enable-e2e-demo-app`.

---

## Task 2: Remove the demo_app iOS skips in `e2e/run_tests`

**Files:**
- Modify: `e2e/run_tests` (lines 129, 153, 154)

- [ ] **Step 1: Verify the lines we expect to remove are present**

Run:
```bash
grep -n 'demo_app has OOM issues on GHA\|demo_app)\s*\[ "\$platform" = "ios"' e2e/run_tests
```
Expected (paths/line numbers may shift if file changed; the three matching lines must all appear):
```
129:		demo_app)        [ "$platform" = "ios" ]     && { echo "skipping $app_name on $platform"; exit 0; } ;;
153:			# demo_app has OOM issues on GHA
154:			demo_app) [ "$platform" = "ios" ]     && continue ;;
```
If any line is missing or has shifted significantly, re-read the surrounding context before editing.

- [ ] **Step 2: Edit the single-app-mode skip (around line 129)**

In `e2e/run_tests`, replace:
```bash
	# Mirror the platform skip rules from the full-suite branch.
	case "$app_name" in
		demo_app)        [ "$platform" = "ios" ]     && { echo "skipping $app_name on $platform"; exit 0; } ;;
		simple_web_view) [ "$platform" = "android" ] && { echo "skipping $app_name on $platform"; exit 0; } ;;
		wikipedia)       [ "$platform" = "android" ] && { echo "skipping $app_name on $platform"; exit 0; } ;;
	esac
```
with:
```bash
	# Mirror the platform skip rules from the full-suite branch.
	case "$app_name" in
		simple_web_view) [ "$platform" = "android" ] && { echo "skipping $app_name on $platform"; exit 0; } ;;
		wikipedia)       [ "$platform" = "android" ] && { echo "skipping $app_name on $platform"; exit 0; } ;;
	esac
```

- [ ] **Step 3: Edit the full-suite-mode skip (around line 154) and remove the OOM comment**

In `e2e/run_tests`, replace:
```bash
		case $app_name in
			# demo_app has OOM issues on GHA
			demo_app) [ "$platform" = "ios" ]     && continue ;;
			simple_web_view)         [ "$platform" = "android" ] && continue ;;
			wikipedia)               [ "$platform" = "android" ] && continue ;;
		esac
```
with:
```bash
		case $app_name in
			simple_web_view)         [ "$platform" = "android" ] && continue ;;
			wikipedia)               [ "$platform" = "android" ] && continue ;;
		esac
```

- [ ] **Step 4: Verify the skips are gone**

Run:
```bash
grep -n 'demo_app has OOM issues on GHA' e2e/run_tests || echo "OOM-comment removed: OK"
grep -nE 'demo_app\)\s*\[ "\$platform" = "ios"' e2e/run_tests || echo "demo_app iOS skip removed: OK"
```
Expected:
```
OOM-comment removed: OK
demo_app iOS skip removed: OK
```

- [ ] **Step 5: Bash syntax check**

Run:
```bash
bash -n e2e/run_tests && echo "syntax OK"
```
Expected: `syntax OK`.

- [ ] **Step 6: Spot-check the surrounding context still reads correctly**

Run:
```bash
sed -n '120,160p' e2e/run_tests
```
Expected: the `case "$app_name" in` blocks in both single-app and full-suite branches still contain the `simple_web_view` and `wikipedia` arms, in valid bash.

- [ ] **Step 7: Confirm tag-filter intent is unchanged**

Run:
```bash
grep -nE 'exclude_tags=' e2e/run_tests
```
Expected (unchanged):
```
22:		android) exclude_tags="ios,web,$cloud" ;;
23:		ios)     exclude_tags="android,$cloud,web" ;;
24:		web)     exclude_tags="android,ios,$cloud" ;;
```
This confirms we still rely on tag-filtering to skip android-only flows on the iOS path.

---

## Task 3: Commit the change

**Files:** none (git only)

- [ ] **Step 1: Show what's about to be committed**

Run:
```bash
git status --short
git diff e2e/run_tests
```
Expected:
- Status shows ` M e2e/run_tests` and `?? docs/` (the docs/ entry must remain untracked).
- Diff shows the three deleted lines and nothing else.

- [ ] **Step 2: Stage only `e2e/run_tests`** (do NOT stage docs/)

Run:
```bash
git add e2e/run_tests
git status --short
```
Expected:
```
M  e2e/run_tests
?? docs/
```
If `docs/` shows up under staged changes, unstage it with `git restore --staged docs/` and re-do the explicit add.

- [ ] **Step 3: Commit (no Co-Authored-By trailer)**

Run:
```bash
git commit -m "$(cat <<'EOF'
e2e: stop skipping demo_app on iOS

The OOM concern that motivated this skip predates the move to macos-26
runners. Remove the dispatcher-level short-circuit so demo_app's
passing-tagged flows run on iOS via the existing test-e2e workflow;
android-only flows are still excluded via tag filtering.
EOF
)"
```
Expected: `[enable-e2e-demo-app <sha>] e2e: stop skipping demo_app on iOS` with `1 file changed, ... deletion(s)`.

- [ ] **Step 4: Verify commit**

Run:
```bash
git log -1 --stat
```
Expected: only `e2e/run_tests` listed under the changed files. If anything else (especially anything under `docs/`) is in there, reset and redo.

---

## Task 4: Push branch and open the draft PR

**Files:** none (gh only)

- [ ] **Step 1: Push the branch and set upstream**

Run:
```bash
git push -u origin enable-e2e-demo-app
```
Expected: branch created on `origin`, upstream tracking set.

- [ ] **Step 2: Open the draft PR**

Run:
```bash
gh pr create --draft --base main --title "e2e: enable demo_app on iOS (draft, iterating)" --body "$(cat <<'EOF'
## Summary

- Remove the dispatcher-level skip of `demo_app` on iOS in `e2e/run_tests` (single-app and full-suite branches) plus the stale `# demo_app has OOM issues on GHA` comment.
- The iOS path of `test-e2e.yaml` already builds `demo_app.app` for the simulator and calls `./run_tests ios`. Tag-filtering in `run_tests` already excludes android-only flows on the iOS path, so this is the entire enablement diff.

## Why draft

Iterating to green: each `test-ios` failure will be diagnosed with the `diagnose-maestro-failure` agent, reported back, and the disposition (fix vs. tag android-only vs. defer) decided per flow. PR will flip to ready-for-review once `test-ios` is green.

## Disposition log

| Flow | Symptom | Disposition | Notes |
|---|---|---|---|
| _(updated as we iterate)_ | | | |

## Test plan

- [ ] `test-e2e.yaml` `test-ios` job runs end-to-end without the runner being killed (no OOM).
- [ ] All cross-platform `passing`-tagged demo_app flows pass on iOS.
- [ ] The `_run_failing` block on iOS still reports the failing-tagged flows as expected failures.
- [ ] No regressions in the existing `test-android` or `test-web` jobs.
EOF
)"
```
Expected: `gh` prints the PR URL. Capture it for the next step.

- [ ] **Step 3: Confirm the PR is in draft and triggered the workflow**

Run:
```bash
gh pr view --json number,isDraft,headRefName,url
sleep 5
gh run list --branch enable-e2e-demo-app --limit 5
```
Expected:
- `isDraft: true`, `headRefName: enable-e2e-demo-app`.
- A recent `Test E2E` run is queued or running on this branch.

If no run appears within ~30s, surface that to the user before continuing.

---

## Task 5: First diagnosis cycle — observe the iOS run

**Files:** none for this task. Subsequent iteration tasks (added below the line by the executor) will reference specific flow YAMLs based on the diagnosis.

- [ ] **Step 1: Watch the run to completion**

Run:
```bash
gh run watch --exit-status $(gh run list --branch enable-e2e-demo-app --limit 1 --json databaseId --jq '.[0].databaseId')
```
Expected: either exits 0 (rare on first try — go directly to "flip out of draft" decision with the user) or non-zero (proceed to diagnosis).

- [ ] **Step 2: Identify the failing job and capture its URL**

Run:
```bash
RUN_ID=$(gh run list --branch enable-e2e-demo-app --limit 1 --json databaseId --jq '.[0].databaseId')
gh run view "$RUN_ID" --json jobs --jq '.jobs[] | select(.name | startswith("Test on iOS")) | {name, conclusion, url}'
```
Capture the iOS job URL — that's what you'll hand to the diagnosis agent.

- [ ] **Step 3: Dispatch `diagnose-maestro-failure` agent against the iOS job URL**

Use the `Agent` tool with `subagent_type: "diagnose-maestro-failure"`. Brief it like a colleague who hasn't seen this conversation:

> Diagnose the iOS demo_app failures from the GitHub Actions run at <URL>. This is the first run on the `enable-e2e-demo-app` branch where we're un-skipping `demo_app` on iOS for the first time, so we expect a number of newly-failing flows. The artifact `maestro-root-dir-ios` (uploaded by the `Test on iOS` job) contains `~/.maestro` including commands JSON, screenshots, and `maestro.log`; `xctest_runner_logs` is also uploaded. **Caveat:** the agent's description says test-android — please confirm whether the iOS artifact shape is comparable enough to produce a useful per-flow report. If something's missing or unfamiliar, say so explicitly rather than guessing. Report a structured per-flow diagnosis with proposed fixes; do NOT edit code.

Capture the agent's report.

- [ ] **Step 4: Summarize each failure + proposed fix to the user**

For each newly-failing flow, present a concise block:
- **Flow:** `e2e/demo_app/.maestro/<path>.yaml`
- **Symptom:** (one line from agent report)
- **Likely cause:** (from agent)
- **Proposed fix:** (from agent — concrete code/yaml or "tag `android`-only", or "defer / file issue")
- **Cascade?** (yes/no — was this caused by an earlier failing flow?)

Then ask the user, per flow, which disposition to apply. Do NOT proactively apply fixes.

- [ ] **Step 5: Stop here for user input**

Iteration tasks below are written by the executor *after* the user picks a disposition for each flow. The executor adds one task per disposition (file, change, verify, commit), pushes, and goes back to Step 1 of this task for the next run. Do not invent fixes ahead of the user's call.

---

## Iteration tasks (added per-cycle)

> Each cycle, append a numbered task here in the form below for every flow disposition the user approved. Keep the disposition log in the PR body in sync.

### Task N (template — instantiate per flow)

**Files:**
- Modify: `e2e/demo_app/.maestro/<path>.yaml` (or other file specific to the fix)

- [ ] **Step 1: Apply the user-approved fix**

Show the exact before/after diff in the plan when instantiated. If the disposition is "tag `android`-only", the change is the tags frontmatter:
```yaml
tags:
  - passing
  - android  # iOS: <one-line reason / link to issue>
```

- [ ] **Step 2: Verify the change locally if feasible**

For YAML tag changes, verify with grep. For flow logic changes, the only meaningful verification is the next CI run.

- [ ] **Step 3: Stage explicit paths and commit (no docs/, no Co-Authored-By)**

Run (substituting actual paths):
```bash
git add e2e/demo_app/.maestro/<path>.yaml
git commit -m "e2e(demo_app): <short why> on iOS"
```

- [ ] **Step 4: Push and re-trigger**

Run:
```bash
git push
```
Then return to **Task 5 Step 1** and re-watch the new run.

---

## Definition of done

- `e2e/run_tests` no longer contains the demo_app iOS skip in either branch, nor the OOM comment.
- A draft PR is open against `main` with `test-e2e.yaml` running.
- Every newly-failing iOS flow has a user-approved disposition recorded in the PR's disposition-log table.
- The `test-ios` job is green on the latest commit.
- The PR has been flipped to ready-for-review (only on explicit user instruction).
