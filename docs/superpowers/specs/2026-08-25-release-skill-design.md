# Spec — one `release` skill for cutting a Maestro release

Written 2026-08-25. Status: approved in conversation, pending written review.

## Why

Cutting a Maestro release is currently described in four places that overlap and disagree:

- `RELEASING.md` (OSS-facing) — prepare PR, tag, wait for Maven Central, publish CLI. Has a "STAFF" callout linking to Notion.
- `.claude/skills/prepare-release` — automates only the changelog + version bump + `ChangeLogUtilsTest`.
- The Notion "Maestro Release Run Book" — mostly stale (legacy monorepo, Robin, "project evergreen"), but is the only place holding the announcement template and the "docs updates" reminders.
- `~/codes/copilot` skills (`bump-maestro`, `canary-rollout`, `monitor-workers`, `prepare-studio-release`) — the private before/after steps.

Two facts from a conversation with another maintainer aren't written down anywhere: the sha being released must already have been deployed to Maestro Cloud and soaked for about a day, and nobody needs to wait for the Maven Central publish before publishing the CLI.

The announcement is already a separate, gated pipeline: `publish-cli` makes jreleaser publish a GitHub release, `notify-release-comms.yml` dispatches on that event to `mobile-dev-inc/release-comms`, which drafts docs/assets and DMs the releaser for approval. Nothing in this repo needs to announce.

## What we're building

One skill, `.claude/skills/release/SKILL.md`, that is both the release doc and the automation. It replaces `prepare-release`. The OSS release lives in the OSS repo; the private steps before and after it are expressed as a precondition and a generic hand-off, not as procedure.

### Files

| Path | Change |
|---|---|
| `.claude/skills/release/SKILL.md` | New. Single source of truth: policy at the top, procedure below, recovery at the end. Single file — no reference docs unless it becomes unwieldy. |
| `.claude/skills/prepare-release/` | Deleted. Its changelog rules and version-bump steps move into the new skill unchanged. |
| `RELEASING.md` | Rewritten as a short pointer: releases are cut with the `release` skill; two-sentence policy summary (sha must be validated on Maestro Cloud first; prepare → tag → publish CLI). Kept at the root only because OSS readers and old links look there. |
| Notion run book | Body replaced with a pointer to `RELEASING.md` and a "superseded" note. Not deleted (bookmarks). The announcement template is dropped — release-comms owns announcements. |

The worktree copy at `.claude/worktrees/milestone4-assertvisible-devicecore/.claude/skills/prepare-release/` is a checkout of an older branch and is not touched.

### Skill inputs

- `version` — `X.Y.Z`, no `v` prefix. Asked for if not given.
- `sha` — the commit to release. Defaults to `origin/main` HEAD; the resolved sha is always shown.

### Skill flow

Exactly one human gate. Everything before it is drafting and is reversible; everything after it runs unattended.

**Step 0 — say what will happen.** Print a concise numbered list of the steps below, marking where the human is involved (the one gate, and the push notification at the end). Then run the clean-main checks: primary checkout (not a worktree), on `main`, clean tree, `0 0` against `origin/main` after `git fetch`. Stop and help if any fail; never `reset --hard` or `clean -fd` without consent.

**Step 1 — draft everything, land nothing on main.**
1. Resolve `sha`. Branch `release/vX.Y.Z` from it.
2. Find the last tag (`git describe --tags --abbrev=0 <sha>`) and list `git log <tag>..<sha> --oneline`.
3. Draft the CHANGELOG entry between `## Unreleased` and the first version header, with the existing editorial rules (strip conventional-commit prefixes, capitalise, keep PR numbers only when the line needs them, match the `Area: description` tone of existing entries).
4. Draft the "Thanks to @a, @b who contributed changes included in this release ❤️" line from the PR authors in the range who are not members of `mobile-dev-inc` (`gh api` on each PR's author + org membership). Omit the line if there are none.
5. Set `VERSION_NAME` in `gradle.properties` and `CLI_VERSION` in `maestro-cli/gradle.properties`.
6. Run `./gradlew :maestro-cli:test --tests "maestro.cli.util.ChangeLogUtilsTest"`. Fix before continuing.
7. Commit `Prepare for release vX.Y.Z`, push the branch, open the PR with `gh pr create` against `main`. A PR is reviewable and reversible, so this is still "before the gate".

**The gate — release checklist.** One message containing everything needed to say yes:
- the sha, the tag range, and the PR link
- the soak precondition, stated as policy: "this commit must already have been deployed to Maestro Cloud and run for about a day. Confirm." (No description of how; maintainers know and it isn't this repo's business.)
- the CHANGELOG diff, for review
- what happens automatically after "go": merge, tag, publish CLI, verify

If the user wants changelog edits, make them, amend/push, and re-present the checklist. Nothing proceeds until an explicit yes.

**Step 2 — merge and tag (unattended).** Wait for PR checks, `gh pr merge --squash --delete-branch`. Fetch, confirm the merge commit's `gradle.properties` carries the version. `git tag -a vX.Y.Z -m "Version X.Y.Z" <merge-commit>` and push the tag. This triggers `publish-release.yaml` (Maven Central); note that it's running and do not wait for it.

**Step 3 — publish CLI (unattended).** `gh workflow run publish-cli.yaml`, find the run id, `gh run watch <id> --exit-status` in the background. jreleaser builds the zip, publishes the `CLI X.Y.Z` GitHub release (tag `cli-X.Y.Z`) and updates the homebrew tap. `PushNotification` on success or failure.

**Step 4 — verify.** In a temp dir, `curl -Ls "https://get.maestro.mobile.dev" | bash` with `HOME`/`PATH` pointed there, and check `maestro --version` prints `X.Y.Z`. Report the actual output either way.

**Step 5 — hand-off.** Generic, so it doesn't change when downstream does: the release is out; downstream consumers (Maestro Cloud, Studio) pick up the tag through their own processes; release-comms will DM the releaser to start the announcement flow (it's triggered by the GitHub release, nothing to do here). No repo paths or submodule names.

### Recovery section (in the skill)

- `ChangeLogUtilsTest` fails → version/changelog mismatch; fix the three files.
- Wrong tag pushed → delete local and remote tag before anything consumed it; if `publish-release` already ran, Maven Central artifacts can't be unpublished — cut a patch release.
- `publish-cli` failed mid-way → jreleaser `overwrite=true` makes re-running the workflow safe.
- PR merge blocked by a red check → diagnose; the gate has passed but nothing irreversible has happened yet, so it's fine to fix on the branch and re-merge.

## What changes about the process itself

Three deliberate differences from today, everything else is the same steps in one place: the soak precondition is written down; "wait for Maven Central" stops being a step; the "Thanks to" line is generated instead of remembered.

## Verification

Dry-run the skill through Step 1 on a throwaway branch (real changelog draft, real version bump, real test run, no PR, no tag), inspect the gate message, then delete the branch and revert. That is exactly the gate boundary, so everything before it is exercised for real. Steps 2–4 are `gh` calls that can't be exercised without releasing; they get a careful read and a comparison against copilot's `prepare-studio-release`, which does the same `gh run watch` + notification dance and is known to work.

## Out of scope

- A copilot-side announce skill (release-comms already covers announcements).
- Changing how copilot bumps or soaks the submodule.
- Changing the CI workflows.
