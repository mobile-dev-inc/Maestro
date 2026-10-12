# Pull requests

How to write the PR body. For commit/title format, see [commits.md](commits.md). Reasoning
reference: Google's [Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html).

## Body

Three sections, each as short as complete.

- **Why** — the problem, and what breaks without this. 1–4 sentences.
- **Approach** — how you solved it, at the design level. For non-trivial changes, say what
  changed *and what didn't*.
- **Verification** — what you actually did to confirm it: repro steps, a screenshot for UI, a
  benchmark. Not "CI passed".

## Ready or draft

Opening a PR asks a human to review it. Open it as ready when it is. Open it as a draft
(`gh pr create --draft`) when there's a reason not to ask yet — you want to see CI pass first,
or it's still missing something — and mark it ready when that's resolved.

## Sensitive information (public repo)

Commits and PR text here are public permanently, and so are the logs and screenshots you
attach. For everyone:

- No secrets, tokens, credentials, or `.env` contents.
- Redact other people's personal details (names, emails, device identifiers) from logs and
  screenshots unless they've agreed to share them.

You're welcome to say who you work for, or what your team uses Maestro for.

## Staff contributors only

For mobile.dev staff, who hold customer and internal context that must not reach this repo.
Reduce it to the code-level fact:

- Never name a customer, org, account, or deployment — say "a customer", "a large deployment".
- Reference internal trackers by ID, never by content ("fixes MA-1234", not what it says or who
  filed it).
- Describe the bug in code terms, not the reporter. No quoted Slack/ticket text. No attributed
  metrics.

No automated check catches a customer name in prose — this is on the author and reviewer.

## No tool attribution

No `Co-authored-by:` for an AI tool, no "Generated with…", no session links — in the title,
body, or commits.
