# Pull requests

How to write a PR. We squash-merge, so the PR title becomes the commit subject on `main`.
Reasoning reference: Google's [Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html).

## Body

Three sections, each as short as complete.

- **Why** — the value: what someone using or maintaining Maestro gets from this that they
  didn't have before, and the issues it resolves (`Fixes #123`). 1–4 sentences.
- **Approach** — how you solved it, at the design level. For non-trivial changes, say what
  changed *and what didn't*.
- **Verification** — what you actually did to confirm it: repro steps, a screenshot for UI, a
  benchmark. "CI passed" is enough only when the change is to CI itself.

Describe a bug by its effect — what a user sees or can't do — not by the defect in the code.
The defect is detail the Approach can cover: the code could change and the bug still stand, and
the reader is a person deciding whether this matters. "A flow with an empty `appId` fails with
an error that doesn't say which flow or field", not "Missing null check in the config parser".

## Ready or draft

Opening a PR asks a human to review it. Open it as ready when it is. Open it as a draft
(`gh pr create --draft`) when there's a reason not to ask yet — you want to see CI pass first,
or it's still missing something — and mark it ready when that's resolved.

## Sensitive information (public repo)

Commits and PR text here are public permanently, and so are the logs and screenshots you
attach. For everyone:

- No secrets, tokens, credentials, or sensitive `.env` contents.
- Redact other people's personal details (names, emails, device identifiers) from logs and
  screenshots unless they've agreed to share them.

You're welcome to say who you work for and what your team uses Maestro for, and to share apps
or other information that helps, such as a reproduction or something already public.

## Staff contributors only

For mobile.dev staff, who hold customer and internal context that must not reach this repo.
Reduce it to what the change does and why it matters:

- Never name a customer, org, account, or deployment — say "a customer", "a large deployment".
- Reference internal trackers by ID, never by content ("fixes MA-1234", not what it says or who
  filed it).
- Describe the bug by its effect, not who reported it. No quoted Slack/ticket text. No
  attributed metrics.

No automated check catches a customer name in prose — this is on the author and reviewer.

## No tool attribution

No `Co-authored-by:` for an AI tool, no "Generated with…", no session links — in the title,
body, or commits.
