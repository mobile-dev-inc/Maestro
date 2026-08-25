# Release Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `RELEASING.md`, the `prepare-release` skill, and the Notion run book with one `release` skill that drives a Maestro release end-to-end with a single human gate.

**Architecture:** The skill is a procedural document (`.claude/skills/release/SKILL.md`) that an agent follows: draft everything on a branch, present one release checklist, then merge / tag / publish CLI / verify unattended. `RELEASING.md` becomes a pointer to it; the Notion page becomes a pointer to `RELEASING.md`. The skill is built TDD-style per superpowers:writing-skills: a baseline run of the release scenario without the skill is recorded first, the skill is written against the failures found, and the scenario is re-run with the skill.

**Tech Stack:** Markdown skill, `git`, `gh` CLI, Gradle (`ChangeLogUtilsTest`), Claude Code tools (`AskUserQuestion`, `PushNotification`, background Bash), Notion MCP.

**Spec:** `docs/superpowers/specs/2026-08-25-release-skill-design.md`

## Global Constraints

- Exactly one human gate in the skill, after everything is drafted and the PR is open, before anything lands on `main`. No second gate before the tag push.
- Nothing in this repo describes copilot internals. The soak requirement is stated as a policy on the sha ("must already have been deployed to Maestro Cloud and run for about a day"), never as a procedure. The hand-off names no repo paths or submodule names; the only external name allowed is `release-comms`, which a workflow in this repo already references.
- The skill does not wait for `publish-release.yaml` (Maven Central).
- The skill does not announce anything; `notify-release-comms.yml` fires on the GitHub release that `publish-cli.yaml` creates.
- Skill frontmatter `description` starts with "Use when" and describes triggers only, never the workflow.
- Skill `name` is `release`; versions have no `v` prefix as input, tags are `vX.Y.Z`.
- Last tag lookup must use `git describe --tags --abbrev=0 --match 'v*'` because `cli-X.Y.Z` tags interleave with `vX.Y.Z`.
- The worktree copy at `.claude/worktrees/milestone4-assertvisible-devicecore/` is not touched.
- Writing follows `~/.claude/writing-style.md`: plain sentences, no corporate vocabulary, no banned phrases.
- No real release is performed while building or testing this. Test runs use the skill's dry-run mode, which stops at the gate and deletes the branch. No PR, tag, or workflow dispatch during testing.

---

### Task 1: Baseline run without the skill (RED)

**Files:**
- Create: `/private/tmp/claude-501/-Users-stevieclifton-codes-Maestro/fee2de28-50aa-4d98-ab6a-7f5f91f837e7/scratchpad/baseline.md`

**Interfaces:**
- Produces: `baseline.md`, a list of what the unassisted agent missed or got wrong. Task 2 reads it to make sure the skill addresses each item.

- [ ] **Step 1: Temporarily hide the existing prepare-release skill so the baseline is clean**

```bash
mv .claude/skills/prepare-release /private/tmp/claude-501/-Users-stevieclifton-codes-Maestro/fee2de28-50aa-4d98-ab6a-7f5f91f837e7/scratchpad/prepare-release.bak
```

- [ ] **Step 2: Dispatch a fresh general-purpose subagent with this prompt, verbatim**

```
You are in /Users/stevieclifton/codes/Maestro, the OSS Maestro repo, on a clean main.
Task: prepare Maestro release 2.9.0 as a DRY RUN. Do everything a maintainer would
do to cut this release up to the point just before anything is pushed to the remote,
then STOP and print (a) the exact message you would show the human before continuing,
and (b) the list of every remaining step you would perform after the human says go,
including anything that happens outside this repo. Do NOT push, do NOT open a PR,
do NOT tag, do NOT trigger any workflow. When done, run `git checkout main` and
delete any branch you created. Report what you did and what you would do next.
```

- [ ] **Step 3: Record the baseline in `baseline.md`**

Check each of these against the subagent's report and write down, verbatim where possible, what it did instead:

- Did it state a precondition that the sha must already be validated on Maestro Cloud? (Expected: no.)
- Did it use `--match 'v*'` for the last tag, or did it pick up a `cli-*` tag? (Expected: `git describe --tags --abbrev=0` returns `v2.8.0` today because `v2.8.0` is newer than `cli-2.8.0`, so it may pass by luck — note whether it guarded for it.)
- Did it bump both `VERSION_NAME` and `CLI_VERSION`? Did it run `ChangeLogUtilsTest`?
- Did it draft a "Thanks to @…" line, and from where?
- Did it plan to wait for Maven Central before publishing the CLI?
- Did it plan more than one human gate after the checklist?
- Did it describe copilot/submodule steps in its post-release list?
- Did it start with a list of what will happen and where the human is involved?

- [ ] **Step 4: Restore the old skill**

```bash
mv /private/tmp/claude-501/-Users-stevieclifton-codes-Maestro/fee2de28-50aa-4d98-ab6a-7f5f91f837e7/scratchpad/prepare-release.bak .claude/skills/prepare-release
git status --short   # expect: clean apart from pre-existing untracked docs/ and package-lock.json changes
```

No commit for this task; `baseline.md` lives in the scratchpad.

---

### Task 2: Write the `release` skill (GREEN)

**Files:**
- Create: `.claude/skills/release/SKILL.md`

**Interfaces:**
- Consumes: `baseline.md` from Task 1 — every failure listed there must map to a line in the skill.
- Produces: the skill, with a `dry-run` mode that Task 3 uses.

- [ ] **Step 1: Create the skill file with this content**

Write the file exactly as below, then adjust only where `baseline.md` shows a failure this text doesn't cover (add a line under "Common mistakes" for each).

````markdown
---
name: release
description: Use when cutting, shipping, or publishing a new Maestro version — "release 2.9.0", "cut a release", "tag vX.Y.Z", "publish the CLI", "update the changelog for the release", or any variant of "let's release maestro".
---

# Release Maestro

This is the release doc for the Maestro repo. There is no other one: `RELEASING.md` points here.

## Policy

- **The commit you release must already have been deployed to Maestro Cloud and run there for about a day.** How that happens is not this repo's business; maintainers know. The skill asks you to confirm it and takes your word.
- **One human gate.** Everything is drafted first (changelog, version bump, PR). You review it all in one message and say go. After that the skill merges, tags, publishes the CLI, and verifies without asking again. Nothing lands on `main` before the gate.
- **Don't wait for Maven Central.** Pushing the tag triggers `publish-release.yaml`; it runs on its own and the CLI publish doesn't depend on it.
- **Don't announce.** `publish-cli.yaml` makes jreleaser publish a GitHub release; `notify-release-comms.yml` fires on that and `mobile-dev-inc/release-comms` DMs the releaser to start the announcement flow.
- **Downstream is not described here.** Maestro Cloud and Studio pick up the tag through their own processes.

## Inputs

| Input | Default | Notes |
|---|---|---|
| `version` | ask | `X.Y.Z`, no `v`. Semver: major = breaking, minor = features, patch = everything else. |
| `sha` | `origin/main` HEAD | Always print the resolved full sha. |
| `dry-run` | off | If the user says "dry run": stop after presenting the gate message, then run the Dry-run cleanup below. Never push, open a PR, tag, or dispatch a workflow in dry-run. |

## Step 0: say what will happen, then check the ground

Print this first, filled in:

```
Releasing Maestro <version> from <sha>.

1. Check we're on a clean main.
2. Branch release/v<version>, draft the CHANGELOG entry, bump the version, run the changelog test, open a PR.
3. >>> YOU: review the checklist (changelog, sha, soak confirmation) and say go. <<<
4. Merge the PR, tag v<version>, push the tag (Maven Central publish starts on its own, we don't wait).
5. Trigger Publish CLI and watch it. You'll get a push notification when it finishes.
6. Install the CLI fresh and check `maestro --version`.
7. Print what happens next outside this repo.
```

Then run the clean-main checks:

```bash
git rev-parse --show-toplevel        # must equal pwd, and be the primary entry in `git worktree list`
git branch --show-current            # main
git status --porcelain               # empty apart from untracked files that can't be committed by accident
git fetch origin --tags
git rev-list --left-right --count origin/main...HEAD   # 0	0
```

If any fail, stop and help the user fix it. Never `git reset --hard` or `git clean -fd` without explicit consent.

## Step 1: draft everything, land nothing

```bash
VERSION=<version>
SHA=$(git rev-parse ${sha:-origin/main})
LAST_TAG=$(git describe --tags --abbrev=0 --match 'v*' "$SHA")   # --match matters: cli-* tags interleave with v*
git checkout -b "release/v$VERSION" "$SHA"
git log --oneline "$LAST_TAG..$SHA"
```

**CHANGELOG.md.** Insert a `## <version>` section between `## Unreleased` and the first existing version header. One bullet per commit, in the existing `Area: description` tone (`Core:`, `Android:`, `iOS:`, `CLI:`, `Web:`). Strip conventional-commit prefixes (`fix:`, `feat(scope):`), capitalise the first word, keep `(#1234)` only when the line is unclear without it. Drop chore/CI/driver-APK commits that don't change user-visible behaviour. Do not touch the `## Unreleased` header itself.

**Thanks line.** For each `(#NNNN)` in the range:

```bash
gh pr view NNNN --json author -q .author.login
gh api "orgs/mobile-dev-inc/members/$LOGIN" >/dev/null 2>&1 && echo member || echo external
```

Skip bots (`dependabot`, `github-actions`). If any externals remain, add after the bullets:

```
Thanks to @a, @b and @c who contributed changes included in this release ❤️
```

If the membership call fails for a reason other than 404 (no org visibility), say so and leave the line out; the user can add it at the gate.

**Version bump.** `VERSION_NAME=<version>` in `gradle.properties`, `CLI_VERSION=<version>` in `maestro-cli/gradle.properties`.

**Test.**

```bash
./gradlew :maestro-cli:test --tests "maestro.cli.util.ChangeLogUtilsTest"
```

It reads `CLI_VERSION` and asserts the CHANGELOG has a non-empty entry for it. Fix before continuing.

**Commit and PR.**

```bash
git commit -am "Prepare for release v$VERSION"
git push -u origin "release/v$VERSION"
gh pr create --base main --title "Prepare for release v$VERSION" --body "Release prep for v$VERSION. Changelog and version bump only."
```

A PR is reviewable and reversible, so this is still before the gate. In dry-run, skip these three commands.

## The gate

Present one message, then `AskUserQuestion` with options `Go` / `Edit the changelog` / `Abort`:

```
Release checklist for v<version>

sha:      <full sha>  (<LAST_TAG>..<short sha>, N commits)
PR:       <url or "dry run — not opened">
changelog:
<the new CHANGELOG section, verbatim>

Confirm: this sha has been deployed to Maestro Cloud and has run there for about a day.

On "go" I will: merge the PR, tag v<version> and push it, trigger Publish CLI and watch it,
install the CLI fresh and check the version. I won't ask again.
```

`Edit the changelog` → apply the edits, `git commit --amend --no-edit && git push --force-with-lease`, re-present the checklist. `Abort` → run Dry-run cleanup and stop.

In dry-run, stop here and run Dry-run cleanup.

## Step 2: merge and tag (unattended)

```bash
gh pr checks "release/v$VERSION" --watch --fail-fast
gh pr merge "release/v$VERSION" --squash --delete-branch
git checkout main && git pull --ff-only origin main
grep -q "VERSION_NAME=$VERSION" gradle.properties || { echo "main doesn't carry $VERSION — stop"; exit 1; }
git tag -a "v$VERSION" -m "Version $VERSION"
git push origin "v$VERSION"
```

The tag push starts `publish-release.yaml` (Maven Central). Note that it's running. Don't wait for it.

## Step 3: publish the CLI (unattended)

```bash
gh workflow run publish-cli.yaml --ref main
sleep 10
RUN_ID=$(gh run list --workflow=publish-cli.yaml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status     # run_in_background: true
```

When it exits, send a `PushNotification`: `Maestro v<version> CLI published` or `Maestro v<version> Publish CLI FAILED`. jreleaser creates the GitHub release `CLI <version>` (tag `cli-<version>`) and updates the homebrew tap; that release event is what triggers release-comms.

## Step 4: verify

```bash
TMP=$(mktemp -d)
HOME="$TMP" MAESTRO_DIR="$TMP/.maestro" bash -c 'curl -Ls "https://get.maestro.mobile.dev" | bash'
"$TMP/.maestro/bin/maestro" --version
```

The output must be exactly `<version>`. Report the actual output either way. If it doesn't match, the CDN may lag a few minutes; retry twice before calling it a failure.

## Step 5: hand-off

Print:

```
Maestro v<version> is released.
- Maven Central publish: <link to the publish-release run> (still running or done; not blocking)
- GitHub release: https://github.com/mobile-dev-inc/maestro/releases/tag/cli-<version>
- Downstream (Maestro Cloud, Studio) pick up the tag through their own processes.
- release-comms will DM you to start the announcement flow. Nothing to do here.
```

## Dry-run cleanup

```bash
git checkout -- CHANGELOG.md gradle.properties maestro-cli/gradle.properties 2>/dev/null
git checkout main
git branch -D "release/v$VERSION"
git status --porcelain   # must be as it was before Step 1
```

## Recovery

| Problem | What to do |
|---|---|
| `ChangeLogUtilsTest` fails | `CLI_VERSION` and the CHANGELOG header disagree, or the section is empty. Fix the three files and re-run. |
| PR checks red after the gate | Nothing irreversible has happened. Fix on the branch, push, re-run Step 2. |
| Wrong tag pushed | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` immediately. If `publish-release` already uploaded to Maven Central, artifacts can't be unpublished: cut a patch release instead. |
| `publish-cli` failed part way | jreleaser has `overwrite=true`; re-run the workflow (`gh workflow run publish-cli.yaml --ref main`) and watch again. |
| `maestro --version` shows the old version | CDN lag. Retry after a few minutes. If it persists, check the jreleaser log printed at the end of the run. |

## Common mistakes

- Pushing the tag before the PR is on `main` (the tag then carries the old version).
- Picking up a `cli-*` tag as the previous version and generating an empty changelog.
- Waiting for Maven Central before publishing the CLI. Nothing needs it.
- Adding a second confirmation before the tag push. The gate already covered it.
- Describing how the sha gets onto Maestro Cloud. State the precondition, don't explain it.
````

- [ ] **Step 2: Check frontmatter and length**

```bash
head -4 .claude/skills/release/SKILL.md      # name: release; description starts with "Use when"
wc -w .claude/skills/release/SKILL.md        # expect roughly 1000-1300 words; it's a procedure, not a hot-path skill
```

- [ ] **Step 3: Cross-check against `baseline.md`**

For every failure recorded in Task 1, find the line in the skill that prevents it. If one isn't covered, add a bullet under "Common mistakes" or a line in the relevant step. Do not add content for hypothetical failures.

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/release/SKILL.md
git commit -m "feat(release): add release skill covering the whole release flow"
```

---

### Task 3: Run the scenario with the skill (GREEN check, then REFACTOR)

**Files:**
- Modify: `.claude/skills/release/SKILL.md` (only if the run exposes a gap)
- Create: `/private/tmp/claude-501/-Users-stevieclifton-codes-Maestro/fee2de28-50aa-4d98-ab6a-7f5f91f837e7/scratchpad/with-skill.md`

**Interfaces:**
- Consumes: the skill from Task 2; `baseline.md` from Task 1.

- [ ] **Step 1: Dispatch a fresh general-purpose subagent with this prompt, verbatim**

```
You are in /Users/stevieclifton/codes/Maestro on a clean main. Use the project skill
`release` (read .claude/skills/release/SKILL.md and follow it). Task: release Maestro
2.9.0 as a DRY RUN. Follow the skill exactly, including its dry-run rules. When you
reach the gate, print the gate message verbatim, then perform the skill's dry-run
cleanup. Report: the Step 0 message you printed, the gate message, the exact commands
you ran, the ChangeLogUtilsTest result, and the final `git status --porcelain`.
```

- [ ] **Step 2: Score the run in `with-skill.md`**

All of these must be true:

- Step 0 message was printed first, with the `>>> YOU` marker on the gate line.
- Previous tag resolved to `v2.8.0` (not `cli-2.8.0`).
- CHANGELOG section for 2.9.0 was drafted between `## Unreleased` and `## 2.8.0`, in `Area:` tone.
- Both `gradle.properties` files bumped to `2.9.0`.
- `ChangeLogUtilsTest` ran and passed.
- No `git push`, `gh pr create`, `git tag`, or `gh workflow run` executed.
- Gate message includes the soak confirmation line and the "I won't ask again" list.
- Cleanup left `git status --porcelain` identical to before and `release/v2.9.0` deleted.

- [ ] **Step 3: Refactor if anything failed**

For each failed check, change the wording of the step the agent got wrong (prefer a more explicit command or a stricter template over a prohibition), re-dispatch with the same prompt, and re-score. Repeat until all checks pass.

- [ ] **Step 4: Verify the repo is clean**

```bash
git status --short          # only the pre-existing untracked docs/ and package-lock.json
git branch --list 'release/*'   # empty
```

- [ ] **Step 5: Commit any skill edits**

```bash
git add .claude/skills/release/SKILL.md
git commit -m "fix(release): tighten skill after dry-run"
```

Skip if there were no edits.

---

### Task 4: Retire the old surfaces

**Files:**
- Delete: `.claude/skills/prepare-release/SKILL.md`
- Modify: `RELEASING.md` (full rewrite)
- Notion page `78159c6f80de4492a6e9e05bb490cf60` (body replaced)

**Interfaces:**
- Consumes: the `release` skill path from Task 2.

- [ ] **Step 1: Delete the old skill**

```bash
git rm -r .claude/skills/prepare-release
ls .claude/skills/prepare-release 2>&1   # No such file or directory
```

- [ ] **Step 2: Rewrite `RELEASING.md` with exactly this content**

```markdown
# Releasing Maestro

Releases are cut with the `release` skill in `.claude/skills/release/SKILL.md`. That file is the release doc; it isn't summarised anywhere else so it can't drift.

The short version: the commit being released must already have been deployed to Maestro Cloud and run there for about a day. Then a prep PR bumps the version and changelog, the merge commit is tagged `vX.Y.Z`, and the Publish CLI workflow is triggered. Publishing to Maven Central runs on its own from the tag and nothing waits on it.
```

- [ ] **Step 3: Check nothing else links to the deleted skill or the Notion page**

```bash
grep -rn "prepare-release\|notion.so/Maestro-Release" --exclude-dir=node_modules --exclude-dir=.claude/worktrees --exclude-dir=.git . | grep -v "docs/superpowers"
```

Expected: no matches. If `CLAUDE.md`/`AGENTS.md` mention `prepare-release`, update them to `release`.

- [ ] **Step 4: Commit**

```bash
git add -A RELEASING.md .claude/skills
git commit -m "docs: point RELEASING.md at the release skill, drop prepare-release"
```

- [ ] **Step 5: Replace the Notion run book body**

Use `mcp__plugin_Notion_notion__notion-update-page` on page `78159c6f80de4492a6e9e05bb490cf60` (load the schema via ToolSearch first). Replace the full content with:

```
> This page is superseded. The Maestro release process lives in the repo: https://github.com/mobile-dev-inc/maestro/blob/main/RELEASING.md (which points at the `release` skill). Announcements are handled by the release-comms pipeline, which is triggered automatically when the CLI is published.
```

Set the `Status` property to `Deprecated` if that option exists in the Runbooks database; otherwise leave it as `Current` and note it in the report. Confirm with `notion-fetch` that the body now shows only the callout.

---

### Task 5: Open the PR

**Files:** none new.

- [ ] **Step 1: Branch and push**

The spec commit (`548bd6e7`) is already on local `main`. Move it and this work onto a branch:

```bash
git branch release-skill
git reset --hard origin/main
git checkout release-skill
git push -u origin release-skill
```

- [ ] **Step 2: Open the PR with this body**

```bash
gh pr create --base main --title "Unify the release process into a single release skill" --body "$(cat <<'EOF'
## Why

Cutting a release was described in four places that overlapped and disagreed: RELEASING.md, the prepare-release skill (which only did the changelog and version bump), a mostly stale Notion run book, and tribal knowledge. Two things weren't written down anywhere: the commit being released needs to have soaked on Maestro Cloud first, and nobody needs to wait for Maven Central before publishing the CLI.

## Approach

One skill, `.claude/skills/release`, is now the release doc and the automation. It drafts everything on a branch (changelog, version bump, PR), stops once for a review, then merges, tags, publishes the CLI, and verifies the install without asking again. The soak requirement is stated as a precondition on the sha, not a procedure, so nothing here describes internal deploy steps. RELEASING.md is a pointer to the skill; the Notion page is a pointer to RELEASING.md. Announcements were already handled by release-comms on the GitHub release event, so the skill just says so.

Spec: docs/superpowers/specs/2026-08-25-release-skill-design.md

## Verification

I ran the skill in dry-run mode against this repo as 2.9.0: it drafted the changelog from v2.8.0..main, bumped both gradle.properties, passed ChangeLogUtilsTest, produced the gate message, and cleaned up. The steps after the gate (merge, tag, publish CLI, verify) can't be exercised without releasing; they're the same gh calls used by the Studio release skill and were read through carefully rather than run.

https://claude.ai/code/session_01F1xxhaEFc6z9trtWykhnGP
EOF
)"
```

- [ ] **Step 3: Report the PR URL**
