# Commit messages

Maestro squashes PRs on merge, so **the PR title becomes the commit subject** on `main`.
These rules govern individual commits and — because of that — PR titles. A CI check
enforces the subject format on every PR title; the body is on whoever merges.

For PR bodies, see [pull-requests.md](pull-requests.md).

## Format: Conventional Commits

```
type(scope): subject
```

- **type** — one of `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`,
  `chore`, `revert`, `style`. `feat` and `fix` are the ones that surface in release notes.
- **scope** (optional) — the area touched: `android`, `ios`, `web`, `cli`, `orchestra`,
  `e2e`, … Match what recent commits use.
- **subject** — imperative mood ("add", not "added"/"adds"), lowercase first letter, no
  trailing period, aim for ≤ 50 characters.
- Breaking change: add `!` before the colon (`feat(cli)!: …`) or a `BREAKING CHANGE:` footer.

Examples:

- `fix(android): don't report an empty input's hint as its text`
- `feat(cli): accept full Android system image path via --device-os`

## Body: only when it adds something

Most commits need no body. Add one when the change is non-obvious — a subtle fix, a
tradeoff, a reason a reviewer would ask about. When you do:

- Explain **why, not how** — the diff already shows how.
- Wrap at ~72 characters.
- Reference the tracker by ID: `MA-1234`, `#3504`. **Never restate what the ticket says,
  who filed it, or which customer it is about** — see
  [pull-requests.md](pull-requests.md#sensitive-information).

## Never in a commit (or PR title)

- **No AI-attribution trailers.** No `Co-authored-by:` line for an AI tool, no "Generated
  with …" note, no `Claude-Session:` or session links. Commits carry no trace of the tool
  that helped write them. (An AI agent must also never add `Signed-off-by` — only a human
  can certify that.)
- **No customer, organization, or deployment names.** This is a public repo. See
  [pull-requests.md](pull-requests.md#sensitive-information).
- No secrets, tokens, keys, or `.env` contents.

## Why the squash body is blank by default

The repo's squash setting is "blank commit message" on purpose. GitHub's alternative fills
the commit body with every branch commit concatenated — WIP messages, attribution
trailers, and all — which is how 300-line commit bodies and stray `Co-authored-by` lines
ended up on `main`. Blank means the person merging writes a real message following these
rules, or keeps just the linted title for a trivial change, instead of rubber-stamping a
dump.
