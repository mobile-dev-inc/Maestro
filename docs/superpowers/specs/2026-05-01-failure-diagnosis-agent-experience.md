# Maestro Failure Diagnosis Agent — Product Experience

Date: 2026-05-01
Status: Draft for review (precedes engineering design)
Audience: Maestro product, Maestro engineering, design partners

This document describes what the **failure-diagnosis agent** feels like to use. Two personas, three surfaces (CLI, Cloud, Studio), one agent. The engineering design lives in a sibling doc once this is approved.

---

## Why this exists

When a Maestro test fails, the artifacts already contain the answer somewhere — `commands-*.json` records the failed step, `maestro.log` records the driver's view of it, the screenshot shows what was on screen, the simulator/emulator/xctest logs record the device's view. Reading all of that across a 30-flow run is tedious and demands knowledge most users don't have. The agent reads it for you and tells you what to do — *in terms you actually act on*.

There are two kinds of "you", and the right answer is different for each:

- **Test author / Cloud user** ("you"): you wrote the YAML flow and want it to pass. You don't know or care how Maestro's Android driver dismisses dialogs. You want a concrete edit to your flow file or your test environment.
- **Maestro maintainer** ("you"): you ship Maestro itself. When a test fails because Maestro mis-handled a system overlay, you want a pointer into `maestro-client/AndroidDriver.kt` and a hypothesis you can verify, not a YAML rewrite.

The same artifacts, the same agent — different rubric. The agent infers persona from the user's prompt or from how they invoke it; surfaces (Cloud / Studio) can also pre-select persona based on context.

---

## The single experience

Whether you're on the CLI, Cloud, or Studio, the loop is the same:

1. **Something failed.** You have a path, an upload id, or a Studio run id.
2. **You ask the agent.** Either by typing into your MCP-connected agent (Claude Code, Claude Desktop, Cursor) or by clicking a "Diagnose" entry point provided by the surface.
3. **The agent reads the right slices** of the artifact tree — failed commands JSON, the relevant log windows, the screenshot at the moment of failure — and produces a verdict shaped to your persona.
4. **You act on the verdict** — edit a YAML, edit Kotlin, file a bug, escalate to the maintainer team.

The artifact tree is the same in all three cases (per the unified-artifacts initiative). The only thing that changes per-surface is **how you got the handle into the agent's hands**.

---

## Persona A — Test author / Cloud user

**Who:** mobile dev or QA who writes Maestro flows. Doesn't read Kotlin. Doesn't read `maestro.log` voluntarily.

**Mental model:** "My test is failing. Tell me what to change in my YAML or my environment so it passes."

**What "good" looks like in the output:**
- One sentence on what failed and why
- A concrete edit, named by file + line + diff, against either:
  - their flow YAML (`e2e/.maestro/foo.yaml`), or
  - their environment (e.g., a permission they need to grant in CI, an emulator flag, an `appId` mismatch)
- A reproducer they can run locally to verify the fix
- An honest "this might be a Maestro bug, not your test — here's how to escalate" when that's the truth

**What never appears:** Kotlin source paths, internal Maestro module names, gRPC error codes uninterpreted.

### A.1 — On the CLI

You ran `maestro test e2e/.maestro/checkout.yaml` and it went red. The terminal printed a debug-output path:

```
> Debug output (also see logs in /Users/you/.maestro/tests):
> /Users/you/.maestro/tests/2026-05-01_135623
```

You open your MCP-connected agent (e.g., Claude Code in a side terminal, with `maestro mcp` already configured as a server). You paste:

> *I just ran a Maestro flow and it failed. Here's the output:*
> *`/Users/you/.maestro/tests/2026-05-01_135623`*
> *I'm a test author — what should I change?*

The agent answers, after a short flurry of tool calls visible to you in the agent's UI:

> **Your `tapOn { id: "checkout-cta" }` on line 14 of `checkout.yaml` timed out after 17 s. The screenshot at the moment of failure shows the system permission dialog *"Allow ShopApp to access photos?"* covering the screen — your app never got focus.**
>
> **Fix:** add a `runFlow: ../helpers/grant-photos.yaml` step before `launchApp`, or pre-grant the permission in your CI emulator setup. Patched diff:
> ```yaml
> # checkout.yaml, before line 8
> - runFlow: ../helpers/grant-photos.yaml
> ```
> **Reproduce:** `maestro test e2e/.maestro/checkout.yaml` after the change.
>
> **Likely Maestro bug?** Probably not — `tapOnElement` correctly reports a UI-blocked timeout. If your flow already had a permission-grant step that previously worked and broke with a Maestro upgrade, escalate via [link to maintainer escalation flow].

That's the whole loop. You apply the patch, re-run, you're done.

### A.2 — On Cloud

Your team's CI ran a flow on Maestro Cloud. The dashboard shows red. You open the run page:

```
cloud.maestro.dev/projects/proj-456/uploads/abc-123
```

The dashboard surfaces a **"Diagnose with AI"** button on every failed flow card. Clicking it does one of two things depending on configuration:

- **Inline mode (default for v1):** opens a side panel with the agent's diagnosis, rendered live, with the same shape as the CLI output above. Behind the scenes, the cloud surface either runs the agent server-side (against locally-available artifacts) or renders the diagnosis from a pre-computed cache.
- **External mode (opt-in):** copies the handle string `cloud:abc-123:proj-456` to your clipboard and shows a hint *"Paste this into your MCP-connected agent and ask for a diagnosis."* For users who already have Claude Code / Cursor open, this is faster than context-switching tabs.

The output is identical in framing to the CLI case — file paths in the verdict still point at *your* flow YAML in your repo, even though the run happened on cloud infrastructure. (The agent never sees your repo; it just produces the path it would expect, based on the flow name in the artifact.)

A natural follow-up the agent should support:

> *"Why did this fail in cloud but pass in my local CLI yesterday?"*

Answer pattern: agent diffs the artifacts of the two runs (or, if the local one isn't available, diffs the failed flow against a passing baseline of the same flow name in cloud history) and reports environmental or timing deltas.

### A.3 — On Studio

You're in Maestro Studio (`localhost:9999`), authoring a flow interactively. You hit "Run", it fails on step 3.

Studio's run panel already shows the per-step result. Below the failed step it adds a single button: **"Ask AI why"**. Clicking it:

1. Opens an inline chat panel scoped to this run
2. The agent has the run handle pre-bound — no copy-paste required
3. The agent's first message is the diagnosis, persona pre-set to `test_author` (Studio assumes test-authorship is the activity)

Because Studio is interactive, the agent can offer follow-ups grounded in *next actions*:

- *"Want me to insert the fix into the flow at line 14?"* → on yes, Studio applies the edit to the open YAML buffer (no commit, just buffer edit)
- *"Want me to re-run the patched flow now?"* → on yes, Studio kicks off a new run with the patched buffer

This turns the Studio diagnosis loop into "fail → diagnose → fix → re-run" without leaving the page. CLI and Cloud don't have this affordance because they don't own the editor or the runner.

---

## Persona B — Maestro maintainer

**Who:** Mobile.dev engineer working on Maestro itself. Reads Kotlin daily. Has opinions about `maestro-client` vs `maestro-orchestra` separation. Triages CI failures on `Maestro/test-android` and `test-ios`.

**Mental model:** "A Maestro test failed. Is this a Maestro bug, a test-flow bug, an environmental flake, or a third-party app bug? If it's a Maestro bug, where in the codebase does the fix live?"

**What "good" looks like:**
- Per-flow classification: test-flow issue / driver gap / framework bug / environmental / app-side / out-of-scope
- For driver-gap or framework-bug rows: a file path into `maestro-client/`, `maestro-orchestra/`, `maestro-android/`, or `maestro-ios-driver/`, with line numbers verified (not guessed)
- **Cascade detection** — when one root failure produces N downstream failures, the agent groups them and proposes one fix per root, not N fixes
- The "trust grep over ❌ screenshot" invariant — the agent never reports a recovered-retry as a regression
- Faithful reproduction of the existing `.claude/agents/diagnose-maestro-failure.md` rubric, which is the institutional knowledge for Android failure triage

**What's encouraged:** referencing internal modules, calling out historical incidents ("this looks like the same class of bug as the one we shipped in 2.4.0"), challenging the user's framing ("you said this was a flake, but the timestamp pattern suggests a real regression").

### B.1 — On the CLI (the most common maintainer path)

You're investigating a CI failure for the `test-android` GHA workflow. You pulled the artifact bundle:

```
gh run download <run-id> --repo mobile-dev-inc/Maestro \
  --name maestro-root-dir-android -D /tmp/run-<id>
```

In your MCP-connected agent:

> *Diagnose `/tmp/run-<id>`. I'm a Maestro maintainer.*

The output mirrors the existing `diagnose-maestro-failure` Claude Code agent's report format — because that report format is the rubric, ported into `get_diagnosis_brief("maestro_maintainer")`:

> **## Summary**
> 5 flows failed in `passing/`. 1 root cause, 1 cascade group of 4, 1 standalone.
>
> **## Root causes**
>
> **### 1. `notification_dialog` — driver gap (cascade root)**
> - **Trigger:** Android system notification permission dialog raised by `targetSdk=34` upgrade, blocking the launcher across `clearState`/`launchApp`/`stopApp`.
> - **Affected flows:** `notification_dialog`, `signin`, `checkout`, `settings` (4 flows)
> - **Cascade?** yes
> - **Proposed fix:**
>   - File: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt:412-428` (verified via Explore subagent)
>   - What it does: extends `dismissAndroidSystemDialogs` to recognize the new `notification_permission` resource id introduced in API 34
>   - Diff: [unified diff]
>   - Side effects: none — additive resource id list
>
> **### 2. `selector_text_match` — test flow issue**
> - File: `e2e/demo_app/.maestro/selector_text_match.yaml:23`
> - The flow asserts on text "Welcome!" but the demo app updated the copy to "Welcome back!" in commit a1b2c3d. Update the assertion.
>
> **## Out of scope**
> - `payments_smoke` — backend flake (HTTP 503 in app logs, not a Maestro issue)
>
> **## Notes**
> The `commands-(retry).json` flow has a `❌` screenshot in its output but every entry is `COMPLETED` — that's a recovered retry, not a regression. Ignored correctly.

This is identical in shape to what the existing `.claude/agents/diagnose-maestro-failure.md` produces today. The product change is that **any maintainer can reproduce this report from their own MCP-connected agent**, not just the agent author with intimate Claude Code agent knowledge.

### B.2 — On Cloud (CI failures, design partner reports)

Maestro itself runs CI on Maestro Cloud for some test surfaces, and design partners file bug reports referencing cloud upload ids. As a maintainer:

> *Diagnose `cloud:abc-123:proj-456`. I'm a Maestro maintainer.*

Same output shape as B.1. The agent fetches artifacts via `CloudArtifactFetcher` and runs the same diagnostic loop.

A maintainer-specific affordance the cloud dashboard surfaces: a **"Show maintainer diagnosis"** toggle on any failed run page. Default is the test-author view (useful for the customer reporting the bug); toggle flips to the maintainer rubric (useful when a Mobile.dev engineer is investigating). Same artifacts, same agent, same handle — different brief.

### B.3 — On Studio

Less common for maintainers but real: when you're reproducing a customer-reported bug locally in Studio, you want maintainer-shaped output, not test-author-shaped output. Studio's "Ask AI why" panel exposes a **persona toggle** in its header (default `test_author`, flippable to `maestro_maintainer`). The toggle is sticky per Studio session.

---

## Cross-cutting behaviors

### What the agent *always* does

1. **Loads the persona brief first** — every diagnosis begins by fetching the rubric for the user's persona. This is observable in the tool-call trace, which makes the behavior auditable and eval-able.
2. **Reads the screenshot at the failed step** — never skips it, even when the JSON+log "look obvious", because system overlays are invisible to JSON and logs.
3. **Trusts `commands-*.json` as the source of truth for which flows actually failed** — `❌` screenshots from recovered retries are ignored.
4. **Cites file paths.** Maintainer mode uses verified line numbers (Explore subagent dispatched). Test-author mode uses the user's own flow file path, derived from the flow name in the artifact.
5. **Groups cascades.** One fix per root cause, never N fixes for a cascade of N flows.

### What the agent *never* does

1. Modify any file. Ever. This is a diagnosis surface, not an edit surface. (Studio's "apply fix" affordance is a Studio feature acting on the agent's *suggested* diff — the agent itself stays read-only.)
2. Escalate test-author output into maintainer output mid-conversation without an explicit user toggle. Personas are sticky per conversation.
3. Refer to internals in test-author mode. If the agent suspects a Maestro bug, it says *"this might be a Maestro bug, here's how to escalate"* — never *"check `AndroidDriver.kt` line 412"*.

### Privacy and visibility

- Artifact contents (screenshots, log lines) are passed as tool results to the connected LLM. Users who run `maestro mcp` locally have full control over which client this is and where the data goes.
- For Cloud's inline mode (server-side agent execution), artifact data flows to whichever LLM provider Maestro Cloud has contracted — disclosed in the "Diagnose with AI" affordance's first-time consent dialog.
- No PII redaction in v1. Out of scope, with an explicit follow-up if customer-data concerns surface.

### Out of scope for v1

- Auto-applying fixes (Studio-side affordance is a wrapper, not part of the agent contract).
- Diff-against-passing-baseline as a built-in feature (agents can do it ad hoc by being given two handles).
- Multi-turn debugging "let's add a logpoint and re-run" flow.
- Detecting flake vs regression statistically across runs.
- A free-text question-answering surface unrelated to a specific run.

---

## What we're committing to

The agent's behavior is identical across CLI, Cloud, and Studio because the artifacts are identical (per the unified-artifacts initiative) and the rubric is fetched from a single tool. The surface-specific differences are *entry points and follow-up affordances*, not the diagnosis itself.

For a test author, the win is: stop reading `maestro.log`. The agent does it for you, in your terms.

For a maintainer, the win is: the institutional triage knowledge in `.claude/agents/diagnose-maestro-failure.md` is now a tool every maintainer can call from their agent of choice, not a one-off Claude Code subagent.

---

## Open product questions

1. **Cloud "Diagnose with AI" inline vs external mode default** — decision affects whether v1 ships with cloud-side agent execution or only the handle-copy hint.
2. **Studio "apply fix" buffer-edit** — included above as a Studio-side enhancement; do we ship it in v1 alongside the agent, or as a fast follow?
3. **Persona inference** — when the user's prompt doesn't say "I'm a maintainer / test author", does the agent ask, or does it default to `test_author` and offer a toggle in its first reply?
4. **Naming.** "Diagnose with AI" is a placeholder. Worth a marketing/UX pass.
