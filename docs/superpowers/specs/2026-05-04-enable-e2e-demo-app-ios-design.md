# Enable demo_app E2E on iOS

**Date:** 2026-05-04
**Branch:** `enable-e2e-demo-app`

## Goal

Stop skipping the Flutter `demo_app` workspace on iOS in our E2E suite, surface whatever fails on the current `macos-26` / iOS 26.1 runner, and iterate on dispositions per failure (fix vs. tag `android`-only vs. defer) until the iOS demo_app run is green.

## Background

`e2e/run_tests` is the dispatcher invoked by `.github/workflows/test-e2e.yaml`. The iOS path of `test-e2e.yaml` already does everything needed to *run* demo_app on iOS:

- Builds the Flutter demo_app for the iOS Simulator (lines 427–433)
- Boots `iPhone 17 Pro` on `iOS26.1`
- Installs apps and calls `./run_tests ios`

But `run_tests` itself short-circuits demo_app on iOS in two places:

- `e2e/run_tests:129` (single-app mode) — `demo_app) [ "$platform" = "ios" ] && { echo "skipping..."; exit 0; } ;;`
- `e2e/run_tests:154` (full-suite mode) — `demo_app) [ "$platform" = "ios" ] && continue ;;`

Both are guarded by the comment `# demo_app has OOM issues on GHA` (line 153). Per discussion, we are treating that comment as stale (predates `macos-26`) and observing what actually happens.

## Tag-filter behavior we rely on

`run_tests` sets `exclude_tags="android,$cloud,web"` when `platform=ios`. The demo_app flow corpus uses tags like `passing`, `passing,android`, or `failing`:

- Cross-platform passing flows (no platform tag) — included on iOS via `--include-tags passing`.
- Android-only passing flows (`passing,android`) — excluded on iOS via `--exclude-tags android`.
- `failing`-tagged flows — picked up by the `_run_failing` block, which expects them to fail.

So *removing the skip alone* is the entire enablement diff. No tag rewrites, no new flow files.

## The change

In `e2e/run_tests`, delete:

- The `demo_app) ... && exit 0;` line in single-app mode (line 129)
- The `demo_app) ... && continue;` line in full-suite mode (line 154)
- The `# demo_app has OOM issues on GHA` comment (line 153)

That's it for "allow running it."

## Workflow

Reuse `.github/workflows/test-e2e.yaml` as-is. PR triggers will pick up demo_app on iOS automatically once the skips are gone. No new workflow file.

## Iteration loop

1. **Open a draft PR** from `enable-e2e-demo-app` → `main`. The PR triggers `test-e2e.yaml` including the `test-ios` job.
2. **When `test-ios` fails**, dispatch the **`diagnose-maestro-failure`** agent against the GHA run/job URL (or against the downloaded `maestro-root-dir-ios` artifact). The agent reads commands JSON, screenshots, and `maestro.log`, classifies each newly-failing flow, identifies cascade groups, and returns a structured report with proposed fixes. It does NOT edit code.
3. **Report each failure + proposed fix to the user.** User decides per-flow disposition: apply the agent's fix, tag the flow `android`-only, defer, escalate, etc.
4. **Apply decisions, push, repeat.** PR stays in draft until the user explicitly says to flip it to ready-for-review.

## Risks and caveats

- **Agent platform scope:** `diagnose-maestro-failure`'s description currently says *test-android run*. The artifact shape (commands JSON / screenshots / maestro.log) is the same on iOS, but the agent has not been validated against iOS artifacts. If the first iOS diagnosis is garbled, we may need a small prompt tweak or fall back to manual inspection of the `~/.maestro` + `xctest_runner_logs` artifacts that `test-ios` already uploads.
- **OOM resurfaces:** If the runner truly OOMs, the failure mode will be visible (job killed, no useful logs). At that point we'll consider sharding the maestro invocations (e.g. root flows + commands/ + issues/ as separate runs). Not pre-emptive.
- **`_run_failing` noise:** The `_run_failing` block executes 8 flows expected to fail. Cross-platform expected-failure flows should still produce the expected non-zero exits on iOS. If a `failing` flow accidentally passes on iOS (because of platform behavior differences), `_run_failing` will report `FAIL! Expected all to fail, but at least some passed instead` — handled the same way as any other failure (report + decide).

## Ground rules during iteration

- Don't tag a flow `android`-only without explicit user say-so.
- Don't disable the `_run_failing` half (or any other slice of the suite) on iOS without explicit user say-so.
- Keep the PR description updated with a running list of each flow's disposition and the reason — so reviewers can see why each tag/skip exists when the PR eventually flips out of draft.

## Out of scope

- New workflow file dedicated to demo_app iOS.
- Pre-emptive sharding for OOM.
- Pre-emptive iOS-specific tagging or fixture changes before any flow forces the question.
- Fixes to the `diagnose-maestro-failure` agent itself unless its iOS output proves unusable.

## Definition of done

- The two skip lines and the OOM comment are removed from `e2e/run_tests`.
- A draft PR is open, triggering `test-e2e.yaml` `test-ios` against the change.
- (Subsequent iterations) Each iOS demo_app failure has been diagnosed and either fixed or assigned a user-approved disposition; the `test-ios` job is green; the PR is flipped to ready-for-review.
