# Pull requests

How to write a PR. We squash-merge, so the PR title becomes the commit subject on `main`.
Reasoning reference: Google's [Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html).

## Body

Three sections, each as short as complete.

- **Why** — the value: what someone using or maintaining Maestro gets from this that they
  didn't have before, and the issues it resolves (`Fixes #123`). 1–4 sentences.
- **Approach** — how you solved it. Call out important changes in behaviour. If there are code
  changes you'd like the reviewer to look at closely, point them out.
- **Verification** — what you actually did to confirm it: tests you added or ran, a screenshot
  or recording for anything visible, a benchmark. Only for changes to CI is "CI passed" enough.
  If the change affects what Maestro does on a device or in a browser, a Maestro flow under
  `e2e/` that shows it working helps — see [`e2e/README.md`](../e2e/README.md).

Describe a bug by its effect — what a user sees or can't do — not by the defect in the code.
The defect is detail the Approach can cover: the code could change and the bug still stand, and
the reader is a person deciding whether this matters. "A flow with an empty `appId` fails with
an error that doesn't say which flow or field", not "Missing null check in the config parser".

## Ready or draft

Opening a PR asks a human to review it. Open it as ready when it is. Open it as a draft
(`gh pr create --draft`) when there's a reason not to ask yet — you want to see CI pass first,
or it's still missing something — and mark it ready when that's resolved.

## Sensitive information (public repo)

Everything pushed or posted here is public permanently: commits, branch names, code and
comments, test fixtures and flows, PR and issue text, review comments, and the logs and
screenshots you attach. For everyone:

- No secrets, tokens, credentials, or sensitive `.env` contents.
- Redact other people's personal details (names, emails, device identifiers) from logs and
  screenshots unless they've agreed to share them.

You're welcome to say who you work for and what your team uses Maestro for, and to share apps
or other information that helps, such as a reproduction or something already public.

## Staff contributors only

If you know which customer, org, account, or deployment a change is for — from a tracker,
Slack, or Maestro Cloud — that's staff context, and it must not reach this repo anywhere listed
above. External contributors don't have it; staff do. Reduce it to what the change does and why
it matters:

- Never name them — say "a customer", "a large deployment".
- Don't include what identifies them either: their app's package or bundle ID, their URLs or
  domains, flows copied from their suite, screenshots or view hierarchies of their app, Maestro
  Cloud org, project, upload, or run IDs, dashboard links.
- Reproduce the bug against the demo app (`e2e/demo_app/`) or a minimal sample, not their app,
  and name the screen and flow after the behaviour, not the customer.
- Reference internal trackers by ID, never by content ("fixes MA-1234", not what it says or who
  filed it).
- Describe the bug by its effect, not who reported it. No quoted Slack/ticket text. No
  attributed metrics.

No automated check catches a customer name in prose — this is on the author and reviewer.

## No tool attribution

No `Co-authored-by:` for an AI tool, no "Generated with…", no session links — in the title,
body, or commits.
