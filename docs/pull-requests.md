# Pull requests

A PR body gives a reviewer a fast, correct mental model of the change: **why** it exists,
the **approach**, and **evidence** it works — with the least reading required. For the
commit and title format, see [commits.md](commits.md).

> **Agents:** open the PR as a draft (`gh pr create --draft`) so a human can review the
> rendered body before it is marked ready.

## Structure

1. **Why** — the problem and what breaks without this. 1–4 sentences.
2. **Approach** — how you solved it, at the design level. For anything non-trivial, say what
   changed *and what did not*.
3. **Verification** — what you actually did to confirm it. Real repro steps, a screenshot
   for UI, a benchmark. Not "CI passed" — that is obvious and it is noise.

Keep it short. Small PRs get a few lines. Push per-file detail under a `## Supporting
changes` heading if you must list it; never narrate the diff inline.

## Sensitive information

**This is a public repo. A commit message or PR body here is published to the entire
internet and cannot be retracted** — forks, clones, and caches put it beyond deletion.
Before you write one, reduce everything you learned from Linear, Slack, logs, or internal
docs down to the code-level fact.

Hard rules:

- **Never name a customer, organization, account, or deployment.** Say "a customer", "an
  enterprise tenant", "a large deployment".
- **Reference the tracker by ID, never by content.** "Fixes MA-1234" — not what the ticket
  says, who filed it, or who it is about. The ID resolves privately.
- **Describe the bug in terms of the code, not the reporter.** "Null tenant config crashes
  the retry loop" — not "customer X's tenant crashed".
- **No quoted or paraphrased Slack or support-ticket text.** State the technical fact, not
  its provenance.
- **Unattributed numbers are fine; attributed ones are not.** "~40k concurrent sessions
  triggers it" is fine; tying that number to a named customer is not.
- **Redact customer data, credentials, and `Authorization`/api-key headers** from any logs,
  stack traces, snapshots, or test fixtures you add.

No automated check can catch a customer name in prose — there is no pattern for it. This one
is on the author and the reviewer. If you are unsure whether something is safe to publish,
leave it out or ask.

> This is the inverse of our private repos, where naming the customer or incident helps
> reviewers. On a public repo, never.

## No tool attribution

No `Co-authored-by:` line for an AI tool, no "Generated with …" note, no session links, in
the title, body, or commits. See [commits.md](commits.md#never-in-a-commit-or-pr-title).

## Style

Write plainly. Facts in bullets. No filler, no jargon, no sentence that just restates the
heading. The body is a tool for the reviewer, not prose to admire.

## Before you mark it ready

- [ ] Why → Approach → Verification, in that order, as short as complete?
- [ ] No customer, org, or deployment name; no quoted internal text; tracker referenced by ID?
- [ ] No AI-attribution trailer or session link in the title, body, or commits?
- [ ] Verification says what you actually did — no "CI passed" filler?
- [ ] Title is a valid Conventional Commit? (It becomes the squash commit subject.)
