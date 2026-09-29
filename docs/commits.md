# Commit messages

Based on [Conventional Commits v1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) (format)
and [Chris Beams, "How to Write a Git Commit Message"](https://cbea.ms/git-commit/) (prose). The
operative rules are inlined below so you don't have to leave the repo; follow the links for the
full rationale, and update this file against those sources if they change.

## Format

```
type(scope): subject
```

- **type** — `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`,
  `revert`, `style`. `feat` and `fix` surface in release notes.
- **scope** (optional) — the area touched: `android`, `ios`, `web`, `cli`, `orchestra`, `e2e`,
  … Match recent commits.
- **subject** — imperative mood ("add", not "added"), lowercase first letter, no trailing
  period, ≤ 50 characters.
- Breaking change: `!` before the colon (`feat(cli)!: …`) or a `BREAKING CHANGE:` footer.

Examples:

- `fix(android): don't report an empty input's hint as its text`
- `feat(cli): accept full Android system image path via --device-os`

## Body (when the change isn't obvious)

- Blank line after the subject; wrap at 72 characters.
- Explain **why, not how** — the diff already shows how.
- Most commits need no body.

## Repo-specific rules

- We squash-merge, so **the PR title becomes the commit subject** and a CI check enforces the
  format. (Lowercase subject resolves the one place Conventional Commits and Beams disagree —
  Beams says capitalize; we don't.)
- Reference trackers by ID, never by content — see
  [pull-requests.md](pull-requests.md#sensitive-information-public-repo).
- No AI-attribution trailers (`Co-authored-by:` for a tool, "Generated with…", session links);
  an agent never adds `Signed-off-by`.
- No customer/org/deployment names, secrets, tokens, or `.env` contents.

## Why the squash body is blank by default

GitHub's alternative concatenates every branch commit into the body — WIP messages, attribution
trailers, and all. Blank means the person merging writes a real message per the above, or keeps
just the linted title for a trivial change.
