You are a senior software architect conducting an INDEPENDENT review of a proposed
rearchitecture of Maestro, the mobile (iOS + Android) E2E test-automation tool. The
Maestro source repository is in your working directory.

Your charter is here — read it in full first:
https://www.notion.so/36e4d3bab4ab8168bfd3cfad5e3722f5
It defines the scope, the standard per-layer mandate, the specific questions to probe,
and the expected deliverables.

How to conduct this review:

1. Reach your OWN conclusions. The linked documents — both the team's existing doc
   sequence (Strategy -> Maestro Core -> API Contract -> Finalizing APIs) and our
   "parallel proposal" (the Vision doc and the Companion Critique) — contain opinions,
   decisions, and recommendations (e.g. "YAML is the public API"). Treat ALL of them as
   claims to validate, never as authority. Do not be swayed by who asserted what.

2. Evaluate everything against the top-level principles and goals, and make every
   recommendation on that basis:
   - (1) architectural simplicity, flexibility, and testability;
   - (2) Maestro's core value proposition — a simple, cross-platform way to interact
     with and test apps — where simplicity must extend to the ARCHITECTURE, not just
     the persistence layer;
   - the overarching goal: a core that is agent-native AND human-friendly, rock-solid,
     fast, and observable.

3. Ground every claim in evidence. Read the actual repo code, with the Android and iOS
   paths examined SEPARATELY (they diverge significantly). For any load-bearing current
   behavior the design leans on, find the PR that introduced it and judge whether its
   original rationale still holds — do not preserve anything for legacy reasons alone.
   Survey other frameworks broadly (search GitHub by android/ios test-automation
   keywords, beyond the obvious). Dispatch deep, focused subagents per layer/aspect and
   let them go as deep as the question needs.

Deliverables:

- A PROPOSED NEXT REVISION of the Vision doc (or a structured revision list it can be
  built from) — the primary deliverable. Justify each change INLINE: how Maestro does it
  today (Android/iOS), what other frameworks do, and why this recommendation. Fold your
  own independent findings together with the Companion Critique's corrections —
  validating them, not adopting them. Write it as a SELF-CONTAINED, standalone document:
  the single authoritative spec. Do NOT reference, diff against, or mention the previous
  revision anywhere; a future reader should see one coherent doc, not a changelog.

- A NAMING PASS (before finalizing): review every name used across the architecture
  against domain-driven-design principles — names must be conventional, domain-accurate,
  and immediately understandable (a reader should "get" them without a glossary).
  Challenge any term that doesn't map cleanly to what it does — e.g. is `observe` the
  right word, or should it be `query` (or another term that maps more directly to how
  it's described)? The architecture must be readable, not just clean.

- A PER-LAYER LEDGER: one line each for L1-L5 and the four contract dimensions (Inputs,
  Observation, Errors, Trace) — `validated as-is` / `changed -> why` / `rejected -> why`
  — so no layer is silently passed over.

- DIRECT ANSWERS to every specific question in the charter (transport; session &
  parallelism; observation/selectors; trace cost; contract completeness; diff-from-today).

- AN OVERALL VERDICT (standalone): is the proposed approach genuinely better, and where
  is it not?

- ANY FATAL FLAWS that would sink the design as written.

Start by reading the charter and the linked docs, then build your own working
understanding of the repo before dispatching your deep-dive subagents.
