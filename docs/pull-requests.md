# Pull requests

How to write the PR body. For commit/title format, see [commits.md](commits.md). Reasoning
reference: Google's [Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html).

## Body

Three sections, each as short as complete. Open PRs as drafts (`gh pr create --draft`).

- **Why** — the problem, and what breaks without this. 1–4 sentences.
- **Approach** — how you solved it, at the design level. For non-trivial changes, say what
  changed *and what didn't*.
- **Verification** — what you actually did to confirm it: repro steps, a screenshot for UI, a
  benchmark. Not "CI passed".

## Sensitive information (public repo)

A PR body here is public permanently. Reduce internal context to the code-level fact:

- Never name a customer, org, account, or deployment — say "a customer", "a large deployment".
- Reference trackers by ID, never by content ("fixes MA-1234", not what it says or who filed it).
- Describe the bug in code terms, not the reporter. No quoted Slack/ticket text. No attributed
  metrics.

No automated check catches a customer name in prose — this is on the author and reviewer.

## No tool attribution

No `Co-authored-by:` for an AI tool, no "Generated with…", no session links — in the title,
body, or commits.
