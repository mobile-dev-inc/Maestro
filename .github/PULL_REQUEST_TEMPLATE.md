<!--
  Keep this body concise: Why → Approach → Verification.
  PUBLIC REPO — do not publish anything that can't be retracted:
    • Never name a customer, org, or deployment. Reference tickets by ID (MA-1234, #123),
      not by their contents.
    • No AI-attribution trailers or session links.
  The PR title becomes the squash commit subject and must be a valid Conventional Commit
  (e.g. "fix(android): …"). See docs/pull-requests.md and docs/commits.md.
-->

## Why

<!-- The problem and what breaks without this. 1–4 sentences. -->

## Approach

copilot:summary

<!-- How you solved it, at the design level. Say what changed and what didn't. -->

## Verification

<!-- What you actually did to confirm it — repro steps, a screenshot for UI, a benchmark.
     Not "CI passed". -->

> **Does this need e2e tests?** The demo app lives in [`e2e/demo_app/`](e2e/demo_app/) — add a test screen and Maestro flow in the same PR. See [`e2e/demo_app/CLAUDE.md`](e2e/demo_app/CLAUDE.md) for details.

## Issues fixed

<!-- Reference by ID only: "Fixes #123", "MA-1234". Do not restate the ticket's contents. -->
