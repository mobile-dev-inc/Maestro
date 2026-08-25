---
name: release
description: Use when cutting, shipping, or publishing a new Maestro version — "release 2.9.0", "cut a release", "tag vX.Y.Z", "publish the CLI", "update the changelog for the release", or any variant of "let's release maestro".
---

# Release Maestro

This is the release doc for the Maestro repo. There is no other one: `RELEASING.md` points here.

## Policy

- **The commit you release must already have been deployed to Maestro Cloud and run there for about a day.** How that happens is not this repo's business; maintainers know. The skill asks you to confirm it and takes your word.
- **One human gate.** Everything is drafted first (changelog, version bump, PR). You review it all in one message and say go. After that the skill merges, tags, publishes the CLI, and verifies without asking again. Nothing lands on `main` before the gate.
- **The PR still needs a maintainer's approval — that's a separate gate from yours.** The skill waits for it after you say go; it doesn't come back and ask you again.
- **Don't wait for Maven Central.** Pushing the tag triggers `publish-release.yaml`; it runs on its own and the CLI publish doesn't depend on it.
- **Don't announce.** `publish-cli.yaml` makes jreleaser publish a GitHub release; `notify-release-comms.yml` fires on that and `mobile-dev-inc/release-comms` DMs the releaser to start the announcement flow.
- **Downstream is not described here.** Maestro Cloud and Studio pick up the tag through their own processes.

## Inputs

| Input | Default | Notes |
|---|---|---|
| `version` | ask | `X.Y.Z`, no `v`. Semver: major = breaking, minor = features, patch = everything else. |
| `sha` | `origin/main` HEAD | Always print the resolved full sha. It must equal `origin/main` HEAD. It's here so you can confirm which commit is being released, not so you can pick an older one. |
| `dry-run` | off | If the user says "dry run": stop after presenting the gate message, then run the Dry-run cleanup below. Never push, open a PR, tag, or dispatch a workflow in dry-run. |

Substitute the concrete version and the full 40-character sha into every command before you run it. Each Bash call is a fresh shell, so `$VERSION`, `$SHA` and `$LAST_TAG` don't survive from one tool call to the next — a guard written with `$SHA` in a later call compares against an empty string.

## Step 0: say what will happen, then check the ground

Print this first, filled in:

```
Releasing Maestro <version> from <sha>.

1. Check we're on a clean main.
2. Branch release/v<version>, draft the CHANGELOG entry, bump the version, run the changelog test, open a PR.
3. >>> YOU: review the checklist (changelog, sha, soak confirmation) and say go. <<<
4. >>> ANOTHER MAINTAINER: approve the PR. main requires one approving review, so ask someone. I'll wait up to 2 hours. <<<
5. Merge the PR, tag v<version>, push the tag (Maven Central publish starts on its own, we don't wait).
6. Trigger Publish CLI and watch it. You'll get a push notification when it finishes.
7. Install the CLI fresh and check `maestro --version`.
8. Print what happens next outside this repo.
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

The three release files have to be untouched. Untracked files are fine. If the second `git status` lists any other modified tracked file, show it to the user and ask before continuing — the lockfile is the common one and has its own Recovery row.

If any of these fail, stop and help the user fix it. Never `git reset --hard` or `git clean -fd` without explicit consent.

## Step 1: draft everything, land nothing

Resolve the sha first and print it:

```bash
git rev-parse origin/main        # or `git rev-parse <the sha the user gave>` if they named one
```

Substitute that full sha into everything below, then check it's main's tip:

```bash
git fetch origin
[ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "the sha isn't origin/main HEAD — stop"; exit 1; }
LAST_TAG=$(git describe --tags --abbrev=0 --match 'v*' <full sha>)   # --match matters: cli-* tags interleave with v*
git checkout -b release/v<version> <full sha>
git log --oneline "$LAST_TAG..<full sha>"
```

If the sha isn't `origin/main` HEAD, stop here, before you draft anything. This skill only releases main's tip. Tell the user they can pass no sha at all, or wait until main is the commit that soaked.

**CHANGELOG.md.** Insert a `## <version>` section between `## Unreleased` and the first existing version header. One bullet per commit, in the existing `Area: description` tone (`Core:`, `Android:`, `iOS:`, `CLI:`, `Web:`). Strip conventional-commit prefixes (`fix:`, `feat(scope):`), capitalise the first word, keep `(#1234)` only when the line is unclear without it. Drop chore/CI/driver-APK commits that don't change user-visible behaviour. Do not touch the `## Unreleased` header itself.

**Thanks line.** For each `(#NNNN)` in the range:

```bash
LOGIN=$(gh pr view NNNN --json author -q .author.login)
gh api "orgs/mobile-dev-inc/members/$LOGIN" >/dev/null 2>&1 && echo member || echo external
```

Skip bots (`dependabot`, `github-actions`). If any externals remain, add after the bullets:

```
Thanks to @a, @b and @c who contributed changes included in this release ❤️
```

A `404` means the user isn't an org member — that's genuinely external. Any other failure (e.g. `403`, no org visibility) means the check couldn't confirm either way: say so and leave the line out; the user can add it at the gate.

**Version bump.** `VERSION_NAME=<version>` in `gradle.properties`, `CLI_VERSION=<version>` in `maestro-cli/gradle.properties`.

**Test.**

```bash
./gradlew :maestro-cli:test --tests "maestro.cli.util.ChangeLogUtilsTest"
git checkout -- maestro-cli/mcp-viewer/package-lock.json
```

It reads `CLI_VERSION` and asserts the CHANGELOG has a non-empty entry for it. Fix before continuing.

The test rewrites `maestro-cli/mcp-viewer/package-lock.json` as a side effect. That change has nothing to do with the release, so discard it — otherwise it rides along on `main` and the next release stops at Step 0 with a dirty tree.

**Commit and PR.**

```bash
git commit -m "Prepare for release v<version>" CHANGELOG.md gradle.properties maestro-cli/gradle.properties
git push -u origin "release/v<version>"
gh pr create --base main --title "Prepare for release v<version>" --body "Release prep for v<version>. Changelog and version bump only."
```

Name the three files instead of using `-am`. The changelog test rewrites `maestro-cli/mcp-viewer/package-lock.json` as a side effect, and `-a` would sweep that into the release commit.

A PR is reviewable and reversible, so this is still before the gate. In dry-run, skip these three commands.

## The gate

Present one message. In a real run, follow it with `AskUserQuestion` offering `Go` / `Edit the changelog` / `Abort`. In dry-run, print the same message and stop — don't call `AskUserQuestion`; the `Edit the changelog` and `Abort` paths below don't apply, since dry-run never opened a PR or pushed a branch. Run Dry-run cleanup and stop.

```
Release checklist for v<version>

sha:      <full sha>  (<LAST_TAG>..<short sha>, N commits)
sha is origin/main HEAD: yes
PR:       <url or "dry run — not opened">
changelog:
<the new CHANGELOG section, verbatim>

Confirm: this sha has been deployed to Maestro Cloud and has run there for about a day.
The PR needs one approving review from another maintainer; I'll wait for it after you say go.

On "go" I will: wait for that approval, merge the PR, tag v<version> and push it, trigger Publish CLI
and watch it, install the CLI fresh and check the version. I won't ask you again.
```

`Edit the changelog` → apply the edits, `git commit --amend --no-edit CHANGELOG.md && git push --force-with-lease` on the `release/v<version>` branch pushed in Step 1, re-present the checklist. `Abort` → if a PR exists, `gh pr close "release/v<version>" --delete-branch` (this already removes the local and remote branch), then run Dry-run cleanup and stop; its own branch delete is then a no-op.

## Step 2: merge and tag (unattended)

```bash
git fetch origin
[ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "main has moved since <full sha> — the new commits haven't soaked, this skill doesn't decide that for you: stop"; exit 1; }
```

Then wait for the maintainer's approval:

```bash
for i in $(seq 240); do        # 240 × 30s = 2 hours
  S=$(gh pr view "release/v<version>" --json state,reviewDecision -q '.state + "/" + .reviewDecision')
  [ "$S" = "OPEN/APPROVED" ] && exit 0
  case "$S" in
    OPEN/CHANGES_REQUESTED) echo "changes requested — stop"; exit 1 ;;
    OPEN/*) sleep 30 ;;
    *) echo "the PR is no longer open ($S) — stop"; exit 1 ;;
  esac
done
echo "no approval after 2 hours — stop"; exit 1
```

Run that in the background — approval can take a while, and running it inline risks the tool timeout. It gives up three ways: two hours with no decision, changes requested, or the PR closed out from under it. On any of those, send a `PushNotification` saying which one happened and stop. Don't merge.

Once it exits zero:

```bash
gh pr checks "release/v<version>" --watch --fail-fast || { echo "PR checks failed — stopping before merge"; exit 1; }
git fetch origin && [ "$(git rev-parse origin/main)" = "<full sha>" ] || { echo "main moved during review — new commits haven't soaked. Stopping."; exit 1; }
gh pr merge "release/v<version>" --squash --delete-branch
git checkout main && git pull --ff-only origin main
grep -q "VERSION_NAME=<version>" gradle.properties || { echo "main doesn't carry <version> — stop"; exit 1; }
git tag -a "v<version>" -m "Version <version>"
git push origin "v<version>"
```

The main-moved guard runs twice on purpose. The first one is before the wait; this one is after it, because review is exactly when someone else lands a commit on main.

The tag push starts `publish-release.yaml` (Maven Central). Note that it's running. Don't wait for it.

## Step 3: publish the CLI (unattended)

```bash
PREV_RUN_ID=$(gh run list --workflow=publish-cli.yaml --limit 1 --json databaseId -q '.[0].databaseId')
git fetch origin && [ "$(git rev-parse origin/main)" = "$(git rev-parse v<version>^{commit})" ] || { echo "main moved past the tag — stop and check before publishing"; exit 1; }
gh workflow run publish-cli.yaml --ref main
RUN_ID="$PREV_RUN_ID"
for i in $(seq 240); do        # 240 × 30s = 2 hours
  sleep 30
  RUN_ID=$(gh run list --workflow=publish-cli.yaml --limit 1 --json databaseId -q '.[0].databaseId')
  [ "$RUN_ID" != "$PREV_RUN_ID" ] && break
done
[ "$RUN_ID" = "$PREV_RUN_ID" ] && { echo "no new publish-cli run appeared after 2 hours — stop"; exit 1; }
gh run watch "$RUN_ID" --exit-status     # run_in_background: true
```

The workflow builds whatever `main` points at, not the tag, so check they're still the same commit before dispatching. Keep the poll and the watch in one Bash call, since `RUN_ID` doesn't survive between calls. If no new run shows up inside two hours, the dispatch didn't take: send a `PushNotification` saying so and stop.

When it exits, send a `PushNotification`: `Maestro v<version> CLI published` or `Maestro v<version> Publish CLI FAILED`. jreleaser creates the GitHub release `CLI <version>` (tag `cli-<version>`) and updates the homebrew tap; that release event is what triggers release-comms.

## Step 4: verify

```bash
TMP=$(mktemp -d)
NOBREW=$(echo "$PATH" | tr ':' '\n' | grep -v -E '^(/opt/homebrew|/usr/local|/home/linuxbrew)' | paste -sd: -)
PATH="$NOBREW" HOME="$TMP" MAESTRO_DIR="$TMP/.maestro" bash -c 'curl -Ls "https://get.maestro.mobile.dev" | bash'
HOME="$TMP" MAESTRO_DIR="$TMP/.maestro" MAESTRO_CLI_NO_ANALYTICS=1 "$TMP/.maestro/bin/maestro" --version
```

`scripts/install.sh` exits with "already managed by a homebrew" when `which maestro` resolves under `/usr/local`, `/opt/homebrew` or `/home/linuxbrew`, which is the normal state on a maintainer's machine. Dropping those directories from `PATH` for that one command gets past it. `java`, `curl` and `unzip` live in `/usr/bin` and `/bin`, so they still resolve; if they don't on this machine the install fails loudly and you'll see it. Only the install line needs the stripped `PATH`.

The output has to contain `<version>`, not equal it. `MAESTRO_CLI_NO_ANALYTICS=1` suppresses the analytics banner, but the CLI can still print an update-available notice, and the install can print dependency output around the version. Report the actual output either way. If it doesn't match, the CDN may lag a few minutes; retry twice before calling it a failure.

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
git checkout -- CHANGELOG.md gradle.properties maestro-cli/gradle.properties 2>/dev/null || true
git checkout -- maestro-cli/mcp-viewer/package-lock.json 2>/dev/null || true
git checkout main
git branch -D "release/v<version>" 2>/dev/null || true
git status --porcelain   # must be as it was before Step 1
```

## Recovery

| Problem | What to do |
|---|---|
| `ChangeLogUtilsTest` fails | `CLI_VERSION` and the CHANGELOG header disagree, or the section is empty. Fix the three files and re-run with `--rerun-tasks` — CHANGELOG.md isn't a declared Gradle input, so a changelog-only edit can report UP-TO-DATE without it. |
| Step 0 says the tree is dirty and the only file is `maestro-cli/mcp-viewer/package-lock.json` | That's the changelog test's side effect from an earlier run, not a real edit. `git checkout -- maestro-cli/mcp-viewer/package-lock.json` and re-run Step 0. |
| PR checks red after the gate | Nothing irreversible has happened. Fix on the branch, push, re-run Step 2. |
| Wrong tag pushed | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` immediately. If `publish-release` already uploaded to Maven Central, artifacts can't be unpublished: cut a patch release instead. |
| `publish-cli` failed part way | jreleaser has `overwrite=true`; re-run the workflow (`gh workflow run publish-cli.yaml --ref main`) and watch again. |
| The install prints "already managed by a homebrew" | `which maestro` resolved under `/usr/local`, `/opt/homebrew` or `/home/linuxbrew`. Re-run the install line with those directories stripped from `PATH`, as Step 4 shows. |
| `maestro --version` shows the old version | CDN lag. Retry after a few minutes. If it persists, check the jreleaser log printed at the end of the run. |

## Common mistakes

- Pushing the tag before the PR is on `main` (the tag then carries the old version).
- Merging without checking `origin/main` still matches the sha from the gate — main may have moved since, and whether those new commits have soaked isn't this skill's call.
- Picking up a `cli-*` tag as the previous version and generating an empty changelog.
- Waiting for Maven Central before publishing the CLI. Nothing needs it.
- Adding a second confirmation before the tag push. The gate already covered it.
- Describing how the sha gets onto Maestro Cloud. State the precondition, don't explain it.
