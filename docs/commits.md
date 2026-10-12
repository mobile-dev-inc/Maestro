# Commit messages

Based on [Chris Beams, "How to Write a Git Commit Message"](https://cbea.ms/git-commit/). The
operative rules are inlined below so you don't have to leave the repo; follow the link for the
full rationale, and update this file against it if it changes.

## Subject

A commit message is read by someone digging through history to find out why the code is the
way it is. Write the subject so it completes the sentence "If applied, this commit will…":
start with the action, soon followed by what it acts on.

- Imperative mood ("Add", not "Added" or "Adds"), capitalized, no trailing period.
- Aim for 50 characters; 72 is the hard limit.
- Say what changes, not which module it lives in. Readers of the history and the changelog
  shouldn't need to know the codebase's layout.

Examples:

- `Don't report an empty input's hint as its text`
- `Accept a full Android system image path via --device-os`

## Body (when the change isn't obvious)

- Blank line after the subject; wrap at 72 characters.
- Explain **why, not how** — the diff already shows how.
- Most commits need no body.

## Repo-specific rules

- We squash-merge, so **the PR title becomes the commit subject**. Write it by the rules above.
- No secrets, tokens, or `.env` contents — see
  [pull-requests.md](pull-requests.md#sensitive-information-public-repo). Staff have further
  rules there on customer names and internal trackers.
- No AI-attribution trailers (`Co-authored-by:` for a tool, "Generated with…", session links);
  an agent never adds `Signed-off-by`.

## Why the squash body is blank by default

GitHub's alternative concatenates every branch commit into the body — WIP messages, attribution
trailers, and all. Blank means the person merging writes a real message per the above, or keeps
just the title for a trivial change.
