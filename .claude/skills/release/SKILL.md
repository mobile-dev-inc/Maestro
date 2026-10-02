---
name: release
description: Use when cutting, shipping, or publishing a new Maestro version — "release 2.9.0", "cut a release", "tag vX.Y.Z", "publish the CLI", "update the changelog for the release", "dry-run a release", or any variant of "let's release maestro".
---

# Release Maestro

This is the release doc for the Maestro repo. There is no other one: `RELEASING.md` points here.

## Policy

- **The commit you release must already have been deployed to Maestro Cloud and run there for about a day.** How that happens is not this repo's business; maintainers know. The skill asks you to confirm it and takes your word.
- **Two human checkpoints.** The version is a judgment call and it gets baked into the changelog, both `gradle.properties`, the branch, the commit, the PR title, and the tag — so you confirm it *before* any of that is written (**Checkpoint 1**). Then everything is drafted into a PR, and you say go once (**Checkpoint 2**); after that the skill merges, tags, publishes the CLI, and verifies without asking again. Nothing lands on `main` before Checkpoint 2.
- **Checkpoint 2 comes after the PR is approved and required checks are green.** `main` requires one approving review and passing required checks (the e2e suite is one). The checklist asks you to get both before you say go; the skill checks them once and merges — it doesn't wait or poll.
- **Propose the version, don't ask for it.** Read the commits since the last release and recommend a bump with the Semver rubric. The human confirms or overrides at Checkpoint 1; they own the call, the skill just does the arithmetic and makes the case.
- **Don't wait for Maven Central.** Pushing the tag triggers `publish-release.yaml`; it runs on its own and the CLI publish doesn't depend on it.
- **Don't announce.** `publish-cli.yaml` makes jreleaser publish a GitHub release; `notify-release-comms.yml` fires on that and `mobile-dev-inc/release-comms` DMs the releaser to start the announcement flow.
- **Downstream is not described here.** Maestro Cloud and Studio pick up the tag through their own processes.

## Inputs

| Input | Default | Notes |
|---|---|---|
| `version` | propose | The skill proposes `X.Y.Z` (no `v`) from the commits since the last tag using the Semver rubric, and you confirm or override at Checkpoint 1. If the invocation names a version, use it — but still map the commits and flag a mismatch at Checkpoint 1 (e.g. the number says minor, the commits are all fixes). |
| `sha` | `origin/main` HEAD | Always print the resolved full sha. It must equal `origin/main` HEAD. It's here so you can confirm which commit is being released, not so you can pick an older one. |
| `dry-run` | off | Only when the invocation explicitly says "dry run". Never offer it as an option or ask about it. In dry-run the skill drafts locally and stops at Checkpoint 2, then runs Dry-run cleanup; it never pushes, opens a PR, tags, or dispatches a workflow. |

Substitute the concrete version and the full 40-character sha into every command before you run it. Each Bash call is a fresh shell, so `$VERSION`, `$SHA` and `$LAST_TAG` don't survive from one tool call to the next — a guard written with `$SHA` in a later call compares against an empty string.

## Semver rubric

Maestro is a CLI plus drivers that run user-authored `.yaml` flows and feed CI. "Breaking" means an existing user's flow or CI integration stops working. Map every commit in the range to a level, drop the chore/CI/driver ones, then **take the highest** — one breaking change makes the release a major, one new feature makes it at least a minor.

| Level | Bump from `X.Y.Z` | What counts |
|---|---|---|
| **Major** | `(X+1).0.0` | A command, flag, selector, or config key is removed, renamed, or changes meaning; a default changes so an existing flow behaves differently; report/output format changes in a way that breaks CI consumers; a supported platform, OS, or runtime is dropped. |
| **Minor** | `X.(Y+1).0` | New backward-compatible capability: a new command, selector, config option, flag, or platform feature. Existing flows keep working unchanged. |
| **Patch** | `X.Y.(Z+1)` | Bug fix, performance, reliability, or internal change that neither adds nor breaks user-facing behavior. Most `fix(...)` commits. |

Chore, CI, and driver-APK commits don't affect the bump (they're also dropped from the changelog). If the range is only those, there's nothing to release — say so and stop.

State the recommendation as arithmetic, not adjectives: "17 commits since v2.8.0 — the highest is a feature (`setDarkMode`…), so minor: 2.8.0 → 2.9.0."

## Step 0: say what will happen, then check the ground

Print this first, filled in:

```
Releasing the next Maestro version from <sha>. Here's what I'll do — you have two checkpoints.

1. Check we're on a clean main and confirm the commit.
2. Read the commits since the last release, propose the version, and draft the changelog.

──▶ CHECKPOINT 1 — you confirm the version (or give me a different one) and the changelog.

3. Branch, write the changelog and version bump, run the changelog test, and open a PR.

──▶ CHECKPOINT 2 — you get the PR approved and its required checks green, confirm the sha
    has soaked on Maestro Cloud for ~a day, then say go.

4. Merge, tag v<version>, and push the tag (Maven Central publish starts on its own; we don't wait).
5. Trigger Publish CLI and watch it — you'll get a push notification when it finishes.
6. Install the CLI fresh and check `maestro --version`.
7. Print what happens next outside this repo.

Nothing lands on main before Checkpoint 2.
```

Then run the clean-main checks:

```bash
git rev-parse --show-toplevel                      # must equal the first path in `git worktree list` (primary, not linked)
git worktree list | head -1
git branch --show-current                          # main
git status --porcelain -- CHANGELOG.md gradle.properties maestro-cli/gradle.properties   # must be empty
git status --porcelain                             # everything else, for the check below
git fetch origin --tags
git rev-list --left-right --count origin/main...HEAD   # 0	0
```

The three release files have to be untouched. Untracked files are fine. If the second `git status` lists any other modified tracked file, show it to the user and ask before continuing — build side effects from an earlier run are the common cause and have their own Recovery row.

If any of these fail, stop and help the user fix it. Never `git reset --hard` or `git clean -fd` without explicit consent.

Then resolve the sha, print it, and confirm it's main's tip:

```bash
git rev-parse origin/main        # or `git rev-parse <the sha the user gave>` if they named one
git fetch origin
[ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "the sha isn't origin/main HEAD — stop"; exit 1; }
```

If the sha isn't `origin/main` HEAD, stop here, before you draft anything. This skill only releases main's tip. Tell the user they can pass no sha at all, or wait until main is the commit that soaked.

## Step 1: propose the version, draft the changelog (Checkpoint 1)

Nothing is written or branched in this step — it's all read-only until the human confirms the number.

Get the previous tag and the commit range:

```bash
LAST_TAG=$(git describe --tags --abbrev=0 --match 'v*' <full sha>)   # --match matters: cli-* tags interleave with v*
git log --oneline "$LAST_TAG..<full sha>"
```

**Recommend the version.** Map each commit to a level with the Semver rubric, drop chore/CI/driver, take the highest, and compute the next version from `LAST_TAG`. If the invocation named a version, keep it but check it against the mapping and flag any mismatch.

**Draft the CHANGELOG.md section.** One bullet per user-visible commit, in the existing `Area: description` tone (`Core:`, `Android:`, `iOS:`, `CLI:`, `Web:`). Strip conventional-commit prefixes (`fix:`, `feat(scope):`) and match the existing casing — the current sections keep the first word lowercase after the area (`Android: don't report…`). The existing sections carry no PR numbers; omit them, and keep a `(#1234)` only if a bullet is genuinely unclear without it. Drop chore/CI/driver-APK commits that don't change user-visible behaviour. You'll insert this between `## Unreleased` and the first existing version header in Step 2 — don't touch the `## Unreleased` header itself.

**Thanks line.** For each `(#NNNN)` in the range:

```bash
LOGIN=$(gh pr view NNNN --json author -q .author.login)
gh api "orgs/mobile-dev-inc/members/$LOGIN" >/dev/null 2>&1 && echo member || echo external
```

Skip bots (`dependabot`, `github-actions`). If any externals remain, add after the bullets:

```
Thanks to @a, @b and @c who contributed changes included in this release ❤️
```

A `404` means the user isn't an org member — that's genuinely external. Any other failure (e.g. `403`, no org visibility) means the check couldn't confirm either way: say so and leave the line out; the user can add it at Checkpoint 2.

**Present Checkpoint 1.** In a real run, print this and follow it with `AskUserQuestion` offering `Confirm <version>` / `Use a different version` / `Edit the changelog`. In dry-run, print it and proceed with the recommended version — don't call `AskUserQuestion`.

```
Version proposal

range:    <LAST_TAG>..<short sha>  (N commits)
levels:   <A> major, <B> feature, <C> fix  → highest is <level>
proposed: <LAST_TAG version> → <version>   (<one-line reason>)

changelog:
<the drafted CHANGELOG section, verbatim>

Confirm the version, or tell me a different one — semver is your call.
```

`Use a different version` → take the number they give, re-derive the changelog header, re-present. `Edit the changelog` → apply the edits and re-present. Only once the human confirms the number do you go to Step 2.

## Step 2: draft everything, land nothing

Substitute the confirmed version into everything below. Create the branch off the confirmed sha:

```bash
git checkout -b release/v<version> <full sha>
```

**Write the files.** Insert the changelog section drafted in Step 1 between `## Unreleased` and the first existing version header. Set `VERSION_NAME=<version>` in `gradle.properties` and `CLI_VERSION=<version>` in `maestro-cli/gradle.properties`.

**Test.**

```bash
./gradlew :maestro-cli:test --tests "maestro.cli.util.ChangeLogUtilsTest"
git status --porcelain | grep -v -E '^ M (CHANGELOG.md|gradle.properties|maestro-cli/gradle.properties)$'   # anything here is a build side effect
```

It reads `CLI_VERSION` and asserts the CHANGELOG has a non-empty entry for it. Fix before continuing.

The tree was clean at Step 0, so any tracked file that's modified now and isn't one of the three release files was rewritten by the build (lockfiles are the usual case). Those changes have nothing to do with the release: `git checkout --` each one. Otherwise they ride along on `main` and the next release stops at Step 0 with a dirty tree.

**Commit and PR.**

```bash
git commit -m "Prepare for release v<version>" CHANGELOG.md gradle.properties maestro-cli/gradle.properties
git push -u origin "release/v<version>"
gh pr create --base main --title "Prepare for release v<version>" --body "Release prep for v<version>. Changelog and version bump only."
```

Name the three files instead of using `-am`, so a build side effect can't get swept into the release commit.

A PR is reviewable and reversible, so this is still before Checkpoint 2. In dry-run, skip these three commands. Once the PR is up, tell the human what they own next: get it approved by another maintainer and required checks green, then come back for Checkpoint 2.

## Checkpoint 2: review and go

Present one message. In a real run, follow it with `AskUserQuestion` offering `Go` / `Edit the changelog` / `Abort`. In dry-run, print the same message and stop — don't call `AskUserQuestion`; the `Edit the changelog` and `Abort` paths below don't apply, since dry-run never opened a PR or pushed a branch. Run Dry-run cleanup and stop.

```
Release checklist for v<version>

sha:      <full sha>  (<LAST_TAG>..<short sha>, N commits)
sha is origin/main HEAD: yes
PR:       <url or "dry run — not opened">
changelog:
<the new CHANGELOG section, verbatim>

Before you say go, you need all three — these are yours, not mine:
  [ ] this sha has soaked on Maestro Cloud for about a day
  [ ] required checks on the PR are green
  [ ] another maintainer has approved the PR

On "go" I will check the approval and required checks once (and stop if either isn't
satisfied), then: merge the PR, tag v<version> and push it, trigger Publish CLI and
watch it, install the CLI fresh and check the version. I won't ask again.
```

`Edit the changelog` → apply the edits, `git commit --amend --no-edit CHANGELOG.md && git push --force-with-lease` on the `release/v<version>` branch pushed in Step 2, re-present the checklist (a force-push dismisses the approval on most branch-protection setups, so the human re-approves). `Abort` → if a PR exists, `gh pr close "release/v<version>" --delete-branch` (this already removes the local and remote branch), then run Dry-run cleanup and stop; its own branch delete is then a no-op.

## Step 3: merge and tag (unattended)

```bash
git fetch origin
[ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "main has moved since <full sha> — the new commits haven't soaked, this skill doesn't decide that for you: stop"; exit 1; }
```

Then check approval and required checks once — the human was asked to get both before saying go:

```bash
S=$(gh pr view "release/v<version>" --json state,reviewDecision -q '.state + "/" + .reviewDecision')
[ "$S" = "OPEN/APPROVED" ] || { echo "PR isn't approved yet ($S) — get it approved, then tell me to continue"; exit 1; }
gh pr checks "release/v<version>" --required --watch --fail-fast || { echo "required checks aren't green — stopping before merge"; exit 1; }
```

`--required` narrows the watch to the checks branch protection actually enforces (the e2e suite among them) and waits for them to finish; `--fail-fast` stops the moment one goes red. If neither is satisfied, stop and say so — don't poll; the human comes back when it's ready. Branch protection also blocks the merge on its own, so this check is here to fail cleanly and early rather than on a cryptic merge error. (If `--required` reports there are no required checks, the repo's protection changed — fall back to `gh pr checks --watch --fail-fast` and tell the user.)

Once both pass:

```bash
git fetch origin && [ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "main moved during review — new commits haven't soaked. Stopping."; exit 1; }
gh pr merge "release/v<version>" --squash --delete-branch
git checkout main && git pull --ff-only origin main
grep -q "VERSION_NAME=<version>" gradle.properties || { echo "main doesn't carry <version> — stop"; exit 1; }
git tag -a "v<version>" -m "Version <version>"
git push origin "v<version>"
```

The main-moved guard runs twice on purpose: once when Step 3 starts and once right before the merge, because review time is exactly when someone else lands a commit on main.

The tag push starts `publish-release.yaml` (Maven Central). Note that it's running. Don't wait for it.

## Step 4: publish the CLI (unattended)

```bash
PREV_RUN_ID=$(gh run list --workflow=publish-cli.yaml --limit 1 --json databaseId -q '.[0].databaseId')
git fetch origin && [ "$(git rev-parse origin/main)" = "$(git rev-parse v<version>^{commit})" ] || { echo "main moved past the tag — stop and check before publishing"; exit 1; }
gh workflow run publish-cli.yaml --ref main
RUN_ID="$PREV_RUN_ID"
for i in $(seq 20); do         # 20 × 30s = 10 minutes
  sleep 30
  RUN_ID=$(gh run list --workflow=publish-cli.yaml --limit 1 --json databaseId -q '.[0].databaseId')
  [ "$RUN_ID" != "$PREV_RUN_ID" ] && break
done
[ "$RUN_ID" = "$PREV_RUN_ID" ] && { echo "no new publish-cli run appeared after 10 minutes — stop"; exit 1; }
gh run watch "$RUN_ID" --exit-status     # run_in_background: true
```

The workflow builds whatever `main` points at, not the tag, so check they're still the same commit before dispatching. Keep the poll and the watch in one Bash call, since `RUN_ID` doesn't survive between calls. If no new run shows up inside two hours, the dispatch didn't take: send a `PushNotification` saying so and stop.

When it exits, send a `PushNotification`: `Maestro v<version> CLI published` or `Maestro v<version> Publish CLI FAILED`. jreleaser creates the GitHub release `CLI <version>` (tag `cli-<version>`) and updates the homebrew tap; that release event is what triggers release-comms.

## Step 5: verify

```bash
TMP=$(mktemp -d)
NOBREW=$(echo "$PATH" | tr ':' '\n' | grep -v -E '^(/opt/homebrew|/usr/local|/home/linuxbrew)' | paste -sd: -)
PATH="$NOBREW" HOME="$TMP" MAESTRO_DIR="$TMP/.maestro" bash -c 'curl -Ls "https://get.maestro.mobile.dev" | bash'
HOME="$TMP" MAESTRO_DIR="$TMP/.maestro" MAESTRO_CLI_NO_ANALYTICS=1 "$TMP/.maestro/bin/maestro" --version
```

`scripts/install.sh` exits with "already managed by a homebrew" when `which maestro` resolves under `/usr/local`, `/opt/homebrew` or `/home/linuxbrew`. Dropping those directories from `PATH` for that one command gets past it. `java`, `curl` and `unzip` live in `/usr/bin` and `/bin`, so they still resolve; if they don't on this machine the install fails loudly and you'll see it. Only the install line needs the stripped `PATH`.

The output has to contain `<version>`, not equal it. `MAESTRO_CLI_NO_ANALYTICS=1` suppresses the analytics banner, but the CLI can still print an update-available notice, and the install can print dependency output around the version. Report the actual output either way. If the download 404s or the version is stale, the CDN is lagging the publish by a few minutes; retry twice before calling it a failure.

This checks the `curl` installer, not Homebrew. To also confirm the brew path without a live install, compare the tap formula to the published artifact (read-only): the `mobile-dev-inc/homebrew-tap` `Formula/maestro.rb` should now pin `<version>`, and its `sha256` should equal `shasum -a 256` of the release's `maestro.zip`. If it matches, `brew install mobile-dev-inc/tap/maestro` will install `<version>`. (Note the tap prefix — the bare name `maestro` resolves to an unrelated cask.)

## Step 6: hand-off

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
git checkout -- CHANGELOG.md gradle.properties maestro-cli/gradle.properties 2>/dev/null || true
git status --porcelain | grep -E '^ M ' | cut -c4- | xargs -r git checkout --   # build side effects from the test
git checkout main
git branch -D "release/v<version>" 2>/dev/null || true
git status --porcelain   # must be as it was before Step 2
```

## Recovery

| Problem | What to do |
|---|---|
| `ChangeLogUtilsTest` fails | `CLI_VERSION` and the CHANGELOG header disagree, or the section is empty. Fix the three files and re-run with `--rerun-tasks` — CHANGELOG.md isn't a declared Gradle input, so a changelog-only edit can report UP-TO-DATE without it. |
| Step 0 says the tree is dirty, but the files aren't ones anyone edited (a lockfile, generated code) | Probably a build side effect from an earlier test run. Show them to the user; with their OK, `git checkout --` those files and re-run Step 0. |
| Wrong version chosen, still at Checkpoint 1 | Nothing's written yet — re-map, propose the corrected number, re-present Checkpoint 1. |
| Wrong version discovered after Step 2 (PR already open) | The number is in the branch name, both `gradle.properties`, the changelog header, the commit, and the PR title. Simplest is to `gh pr close "release/v<wrong>" --delete-branch`, run Dry-run cleanup, and start again from Step 2 with the right version. |
| Required checks red after Checkpoint 2 | Nothing irreversible has happened. Fix on the branch, push, get it re-approved and green, re-run Step 3. |
| Wrong tag pushed | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` immediately. If `publish-release` already uploaded to Maven Central, artifacts can't be unpublished: cut a patch release instead. |
| `publish-cli` failed part way | jreleaser has `overwrite=true`; re-run the workflow (`gh workflow run publish-cli.yaml --ref main`) and watch again. |
| The install prints "already managed by a homebrew" | `which maestro` resolved under `/usr/local`, `/opt/homebrew` or `/home/linuxbrew`. Re-run the install line with those directories stripped from `PATH`, as Step 5 shows. |
| `maestro --version` shows the old version, or the download 404s | CDN lag behind the publish. Retry after a few minutes. If it persists, check the jreleaser log printed at the end of the run. |

## Common mistakes

- Asking the human for the version instead of proposing one from the commits.
- Offering dry-run as an option. Only honor it when the invocation explicitly asks for it.
- Pushing the tag before the PR is on `main` (the tag then carries the old version).
- Merging without checking `origin/main` still matches the sha from Checkpoint 2 — main may have moved since, and whether those new commits have soaked isn't this skill's call.
- Skipping the required-checks check on go because "branch protection will catch it" — check explicitly so you stop cleanly instead of on a failed merge.
- Picking up a `cli-*` tag as the previous version and generating an empty changelog.
- Waiting for Maven Central before publishing the CLI. Nothing needs it.
- Adding a confirmation before the tag push. Checkpoint 2 already covered it.
- Describing how the sha gets onto Maestro Cloud. State the precondition, don't explain it.
