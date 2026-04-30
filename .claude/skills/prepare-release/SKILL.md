---
name: prepare-release
description: Use this skill when the user asks to "prepare a release", "update the changelog", "bump the version", "prepare release notes", or "cut a release". Automates changelog generation from git commits since the last tag and version bumping across gradle.properties files.
version: 1.0.0
---

# Prepare Release Skill

Automates the three-file release-prep workflow: CHANGELOG.md entry, and version bumps in both gradle.properties files.

## Steps

### 1. Determine the version number

Check if the user has provided a version number in their message. If not, use `AskUserQuestion` to ask:

> "What version number should this release be? (e.g. 2.5.1)"

Use the version exactly as provided (no `v` prefix).

### 2. Find commits since the last tag

Run these two commands:

```bash
git describe --tags --abbrev=0
```

Then use the resulting tag (e.g. `v2.5.0`) to list commits:

```bash
git log <tag>..HEAD --oneline
```

### 3. Update CHANGELOG.md

File: `CHANGELOG.md`

Insert a new version section **between** the `## Unreleased` line and the first existing version header. Format:

```markdown
## <version>

- <entry 1>
- <entry 2>
...
```

**Editorial rules for commit → entry conversion:**
- Each commit becomes one `- ` bullet point.
- Strip conventional-commit prefixes (`fix:`, `feat:`, `fix(scope):`, etc.) and capitalise the first word.
- Remove PR number suffixes like `(#1234)` only if the commit message is already clear without them; otherwise keep them.
- Rewrite for clarity and consistency with the existing CHANGELOG tone (sentence case, imperative or noun phrases).
- Do **not** add a "Thanks to" contributors line — that is added manually.

### 4. Update version numbers in both gradle.properties files

Update these two files so all three files carry the same version:

**`gradle.properties`** — change the `VERSION_NAME` line:
```
VERSION_NAME=<new version>
```

**`maestro-cli/gradle.properties`** — change the `CLI_VERSION` line:
```
CLI_VERSION=<new version>
```

### 5. Run ChangeLogUtilsTest

```bash
./gradlew :maestro-cli:test --tests "maestro.cli.util.ChangeLogUtilsTest"
```

The key test (`test format last version`) reads `CLI_VERSION` from `maestro-cli/gradle.properties` and asserts that the CHANGELOG contains a non-empty entry for that version. All three files must be consistent for the test to pass.

Report the test result. If it fails, diagnose and fix before finishing.
