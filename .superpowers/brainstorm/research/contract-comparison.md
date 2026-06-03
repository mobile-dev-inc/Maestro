# Maestro Core Contract: Approach A vs Approach B — A Fair Comparison

> A = the "control plane" proposal (`worked-example-core-contract.md`).
> B = the internal "Maestro Core: Finalizing APIs" Notion design (`MaestroSession`/`FlowEvent`).
> This document steelmans B before judging, because B is closer to today's shipping
> architecture (Orchestra as a YAML-driven flow runner over a Driver, snapshot-poll
> synchronization, exceptions for transport/JS failures) and therefore carries far
> less migration risk than A. The goal is an honest verdict, not advocacy for A.

---

## TL;DR verdict

**A is the better north-star architecture; B is the better next sprint.** They are
not really competing at the same altitude. B is a concrete, low-risk API surface that
formalizes what Maestro already does (run a flow, stream events, throw on transport
errors). A is a re-architecture of the *core contract* that happens to subsume B's
flow-runner as one consumer. The honest recommendation is **adopt A's four type
decisions** — typed Command IR, targeted observation + Element model, errors-as-values
with a who-fixes-it category, and a nested span trace — **but stage them through B's
shipping shape** so you do not stall the team on a big-bang rewrite. The single most
important thing A gets right and B gets wrong is the **values-vs-thrown error
contradiction**: B's own reviewer already flagged it, and it is a real correctness bug,
not a style preference. The single place A is genuinely over-engineered relative to
present need is the **on-device-agent / event-driven-sync rebuild**, which is a large
cost A bundles in but does not have to be coupled to the contract work.

---

## Dimension 1 — Inputs / IR (YAML-as-API vs typed Command IR)

**A:** The core consumes a typed, versioned, cross-platform `Command` discriminated
union. YAML is demoted to *one adapter*: `parse(yaml) -> Flow`, whose output is the
identical `Command[]` an agent could build by hand in TypeScript. `schemaVersion`
lets the IR evolve without breaking adapters.

**B:** The input *is* the YAML / flow path (`Workspace.load(path)`,
`MaestroFlow.parse(yaml)`). Core's stated role begins with "validate (schema + runtime
JS)." YAML is the API; the typed objects exist mainly as the parse target.

**Reality check (today):** Maestro already has a typed command model
(`maestro-orchestra-models`: `Commands.kt`, `MaestroCommand`) that YAML parses into
(`YamlCommandReader`). So A's "YAML is just an adapter" is *partially already true in
the code* — the typed IR exists. What B does is keep YAML as the *public contract* and
treat the typed model as an internal detail; what A does is promote the typed model to
*the* public contract and make YAML one of several front-ends.

**Does YAML-as-API-vs-typed-IR actually matter? Yes, but only if agents are a
first-class consumer.** If the only consumers are human-authored YAML tests + CLI, B's
choice is fine and simpler — there is exactly one authoring surface, so making it the
API removes a layer. The moment a *second* programmatic producer exists (an LLM agent,
a record/replay recorder, a code-gen tool, a different DSL), B forces every one of them
to round-trip through YAML string generation — which is lossy (no `ref` binding to a
live element), awkward to construct correctly, and re-parses on every call. A's IR lets
those producers emit `Command` values directly and lets the agent express
`{ ref: "e3" }` (bound to a live observation) — something YAML *structurally cannot
express* because the author has no live observation. That `ref` gap is the cleanest
proof that YAML is not a sufficient API for agentic use.

**Winner: A, conditionally.** A is clearly better *if and only if* non-YAML producers
matter (and the whole rearchitecture premise says they do). If they never will, B is
simpler and A's `schemaVersion`/adapter framing is overhead. Given the stated direction
(agent-native), A wins — and the win is cheap because the typed model already exists.

---

## Dimension 2 — Observation model

**A:** Cheap, *targeted* `observe(query: Selector) -> Element[]` as the default, with
`snapshot({maxDepth})` as a rare fallback. A typed cross-platform `Element` (role,
name, value, state, bounds, `visible`, `hittable`, `scrollable`, opaque `ref`). Host
owns all matching/scoring/`nth`; device only captures raw a11y nodes. Includes a dense
one-line-per-element LLM projection.

**B:** `suspend fun viewHierarchy(): ViewHierarchy` — the full tree, on demand.

**Reality check:** Maestro today is exactly B — it pulls a full view hierarchy and
matches against it (the snapshot-poll model). This is the single biggest source of
Maestro's real-world pain: hierarchy dumps are large, slow on complex screens (RN,
WebViews, big lists), and the matching/`nth`/disambiguation logic is non-trivial. So
B's model is "the thing we know hurts," and A's targeted-observe is a direct response.

**Honest counterpoint for B:** full-hierarchy has real virtues A glosses over.
(1) It is *complete* — you can answer any query, including "what else is on screen,"
without a second round trip; A's `candidates` array is precisely A re-inventing "show me
the neighbors" because targeted observe threw away the context. (2) Matching against a
local snapshot is *deterministic and debuggable* — one capture, all matching host-side,
reproducible from the saved hierarchy. (3) `observe(query)` pushing a *selector* to the
device implies the device understands selectors, or you still capture broadly and filter
host-side (in which case "cheap targeted" is a host-side filter, not a cheaper capture).
A's doc is slightly hand-wavy here: it says the host owns all matching *and* that
observe is cheap — those are in tension unless the device-side capture is itself scoped,
which is the hard part A does not fully specify.

**Where A is genuinely better:** the `Element` model with `hittable` (Playwright-style
actionability) baked into the type is a real upgrade over a raw hierarchy node — it
encodes occlusion/enabled/visible as a first-class fact instead of leaving every caller
to recompute it. And the LLM projection is a concrete, correct answer to "how does a
model read this without 50KB of XML."

**Winner: A on the type model and projection; a tie-to-mild-B on completeness.** Best
outcome: A's `Element` + `hittable` + projection, but keep a `snapshot()` escape hatch
(A already includes it) so you never lose the "what else is here" power that full
hierarchy gives for free. B alone perpetuates the known performance problem.

---

## Dimension 3 — Error model

**A:** Outcomes are *values*. Every command returns `CommandResult = {ok:true,...} |
{ok:false, error}`. `MaestroError` carries a stable machine `code`, a `category`
(author / test / app / infra = "who fixes it"), `retryable` + `retryAfterMs`, the
`selector`, near-miss `candidates`, and artifact refs. *Nothing throws across the
boundary.*

**B:** Mixed. `MaestroError` (TestFailure, AppCrash) is a **value** inside
`CommandFailed`. BUT `DeviceDisconnectException` and `JsEvaluationException` are
**thrown** and escape the `Flow<FlowEvent>` stream. B's own reviewer flagged this
contradiction.

**This is the clearest, least-debatable win for A.** The dual representation in B is
not a stylistic wart — it is a correctness/usability bug:

1. **It breaks the stream contract.** A consumer doing `runFlow(flow).collect { ... }`
   handles `CommandFailed` as data but must *also* wrap the whole collection in
   try/catch for disconnect/JS errors. Two error channels for one logical concept
   ("the command didn't succeed") means every consumer must implement both, and the
   ones who forget the second silently crash on infra blips — exactly the failures you
   most want to *retry*, not propagate.
2. **It puts the most-retryable failures in the least-retryable channel.** Device
   disconnect is the textbook "retry me" case; throwing it makes retry logic live
   outside the per-command result loop, the opposite of where you want it. A's
   `retryable:true / retryAfterMs` on the *value* puts the retry signal exactly where
   the retry loop reads it.
3. **It loses fault attribution.** A thrown `DeviceDisconnectException` has no
   `category`; A models the identical event as `{code:"DEVICE_DISCONNECTED",
   category:"infra", retryable:true}` — same boundary, opposite handling, uniform shape.

**The facts-vs-verdict idea (A's subtler contribution):** A's framing is that the core
reports *facts + evidence* (the assertion timed out; here are the `candidates` actually
on screen; here is the screenshot/observation ref) and assigns *fault* only where fault
is a determinable fact (a crash is unambiguously `app`; a disconnect is unambiguously
`infra`). The agent's self-correction snippet — finding the "Invalid password" banner in
`candidates` and concluding "real bug, not flaky locator" — is the payoff: structured
evidence lets the caller reach a *verdict* the core deliberately did not pretend to
know. This is genuinely better than B, where a `TestFailure` carries
`hierarchyAtFailure` + `screenshotAtFailure` (good!) but no near-miss `candidates` and
no `category`/`retryable`, so the caller must re-parse the hierarchy to do what A hands
it directly.

**Honest counterpoint for B:** values-everywhere has a cost A underplays. Truly
*exceptional* conditions (the session object is dead, a bug in core itself) arguably
*should* throw — forcing them into `CommandResult` can mask programming errors that a
stack-trace-bearing exception would surface loudly. And exceptions in Kotlin/coroutines
are idiomatic; an all-values API fights the language's grain a bit and can produce
`if (!r.ok)` boilerplate at every call. So "nothing ever throws" is slightly dogmatic;
the right line is "*expected operational outcomes* (assertion fail, not found, crash,
disconnect, timeout) are values; *programming/contract violations* (null session,
illegal argument) may still throw." A's doc would be stronger if it drew that line
explicitly instead of "nothing throws."

**Winner: A, decisively** — and B's contradiction is a concrete defect that must be
fixed regardless of which overall direction is chosen. Even a B-shaped API should unify
disconnect/JS errors into the `CommandFailed`/event channel with a category + retryable.

---

## Dimension 4 — Trace / Events

**A:** A nested **span tree** (`run → flow → command → retry → deviceAction / wait`)
emitted as an append-only event log (`SpanStarted` / `SpanEnded` / `SpanEvent` /
`ArtifactRef`). One stream serves both live UI and on-disk NDJSON — no second
serialization path to drift. Nesting via `parentSpanId`; point-in-time `SpanEvent`s
record poll ticks and pushed `device.idle`. Two consumers: a live progress bar renders
only top-level spans; an eval/agent walks children to *prove* sync was event-driven and
the element genuinely never appeared.

**B:** A **flat** `Flow<FlowEvent>` list (`FlowStarted`, `CommandStarted/Completed/
Failed/Skipped/Warned`). Nesting is a single integer: `CommandIndex(flat, depth)`.
B's own comments flag two open questions: how to represent **intermediate events for
retries/composite commands** (e.g. `runFlow`, `repeat`, `retry`).

**Reality check:** B's flat list is essentially today's event model and maps cleanly
onto the existing `Orchestra` command-by-command execution. It is dead simple to
consume: a CLI reporter or JUnit XML writer just folds over the list. For the *batch
flow-runner* use case, B is genuinely adequate and lower-friction.

**Where the `depth:Int` model breaks down — and this is B's own admitted gap:** a flat
list with a depth integer cannot faithfully represent a *tree* of execution. The moment
you have composite commands (`runFlow`, `repeat`, `retry`, conditionals) — which
Maestro already has — "depth as an int" can tell you *how nested* a command is but not
*which parent it belongs to*, not how many times a retry fired, and not the
sub-operations (wait-for-actionable, the actual device tap, poll ticks) inside one
command. B's open comment about "intermediate events for retries/composites" is exactly
the symptom of choosing a flat structure for tree-shaped data. You either bolt on
parent pointers (reinventing A's `parentSpanId`) or you flatten and lose fidelity.

**Where A is better and where it is heavier:** A's span tree is the correct shape for
tree-shaped execution and directly answers the retry/composite question B left open.
The "one stream, two consumers, no serialization drift" property is a real
architectural win (Studio live feed == on-disk NDJSON). The cost: spans are more to
implement and emit, and a naive live consumer must learn to *ignore* child spans (A
addresses this — render only top-level — but it is a consumer-side concern that B's flat
list never imposes). For simple reporters, A is more than they need.

**Winner: A on correctness/fidelity** (it solves B's stated open problem by
construction), **B on consumer simplicity for the batch case.** Pragmatic synthesis:
emit A's span events, but provide a trivial "top-level only" filter so simple consumers
get B-like flatness for free. That preserves B's ease without B's fidelity ceiling.

---

## Dimension 5 — Transport & Synchronization

**A:** Event-driven sync — the device *pushes* `idle`/`changed`; the host runs
Playwright-style **actionability** checks (wait until `hittable:true`) before acting.
The trace shows a `wait` span fed by a pushed `device.idle` with the actionability
transition recorded, explicitly *not* a sleep.

**B:** Unaddressed. The doc does not specify transport/sync, which (the prompt notes)
implies today's **snapshot-poll** model: capture hierarchy, check, sleep, repeat.

**Reality check:** snapshot-poll is exactly what Maestro does now, and it is the second
major pain source after hierarchy size — polling is the root of both flakiness ("we
checked at the wrong moment") and slowness (fixed waits / repeated full captures). A is
attacking a real, known problem.

**But this is also where A is most over-engineered relative to risk.** Event-driven sync
with a device *push* channel is a **large** change: it requires the on-device agent
(iOS XCTest runner, Android instrumentation) to detect idle/changed and push events
over a bidirectional transport — touching `maestro-ios-xctest-runner`,
`maestro-android`, and the proto/transport layer. That is arguably the most expensive
single item in A, and it is *separable* from the contract work: you can ship A's typed
IR, Element model, error values, and span trace while *still* polling underneath, then
swap the sync mechanism later without changing the public contract. A's doc bundles
event-driven sync into the proposal, which inflates its perceived cost and risk.

**Honest point for B:** by *not* specifying transport, B avoids committing to a risky
rebuild and stays shippable on the current transport. That is a feature of B's
conservatism, not just an omission — though it also means B inherits today's flakiness.

**Winner: A on the destination** (event-driven + actionability is unambiguously the
right end state and is what mature competitors do), **but the cost is real and should be
decoupled.** Recommend: adopt A's *contract* now, treat event-driven sync as a
follow-on that the contract is designed to accommodate but does not require on day one.

---

## Dimension 6 — Device / Session / Platform handling

**A:** Platform is **explicit**: `connect({deviceId, platform:"ios"})`, followed by
`session.capabilities()` to negotiate what the device supports (observe,
eventDrivenSync, ...). Capability negotiation makes feature variance first-class.

**B:** `MaestroSession.connect(deviceId, input)` with platform **magic-detected** from
the `deviceId`. `SessionInput(artifactsDir, flowTimeout)`. Clean `Closeable` lifecycle
with `.use { }`.

**Reality check:** Maestro today does infer platform/driver from the device (the CLI
figures out iOS vs Android). So B matches current behavior and is ergonomic — callers
don't have to know/pass platform.

**Trade-off:** B's magic-detection is more convenient for the 95% case and matches
today. A's explicit platform + `capabilities()` is more honest about a real problem:
iOS and Android (and web) genuinely differ in what they can observe/do, and a
capability handshake lets callers (especially agents) adapt instead of hitting runtime
"not supported on this platform" surprises. The cost is one more line and the risk of
the caller passing the *wrong* platform (which detection avoids).

**B's `Closeable`/`.use {}` lifecycle is genuinely nice** and A's doc doesn't emphasize
resource cleanup as crisply — that is a small point for B.

**Winner: roughly even, leaning A for the agent use case.** Detection (B) is better DX
for humans/CLI; explicit platform + capability negotiation (A) is better for
heterogeneous programmatic consumers and avoids silent capability mismatches. The ideal
keeps B's detection as a *default* (`platform` optional, inferred if omitted) and adds
A's `capabilities()` — they are not mutually exclusive. Keep B's `Closeable`.

---

## Dimension 7 — Agent-nativeness (perceive-act primitives vs flow-only)

**A:** The core exposes perceive-act *primitives* directly: `observe → run(command) →
observe` in a tight loop. The batch flow-runner is *one consumer* built on the same
primitives. An agent and a YAML test share the identical control plane, Element model,
error taxonomy, and trace — only the authoring surface differs.

**B:** Flow-first. The primary verb is `runFlow(flow): Flow<FlowEvent>`. There are
point primitives (`takeScreenshot`, `viewHierarchy`) but no first-class "run a single
command and get a structured result" primitive, and no `ref`-based element binding. An
agent must assemble a YAML/flow, run it, and consume events — coarser-grained than
observe-act-observe.

**Reality check:** Maestro's whole engine (`Orchestra`) is built around running a
*list* of commands. Single-command execution exists internally but is not the public
contract. So B reflects the engine's grain; A asks the engine to expose a finer grain.

**Where A is clearly better for agents:** the tight perceive-act loop with `ref`
binding and structured per-command results is *what an LLM agent actually needs* —
observe to get refs, act on a ref, read the structured outcome, observe again. B forces
the agent up to flow granularity, loses the `ref` binding (Dimension 1), and returns an
event *stream* per run rather than a direct result per action. For the stated
agent-native goal, A is the materially better fit.

**Honest counterpoint:** if agents turn out to be a *minor* consumer and the product
stays test-suite-centric, A's primitive-first framing is solving a problem B doesn't
have, and the extra public surface (every primitive is now API you must support
forever) is a liability. A also slightly *complicates the simple case*: a human who just
wants to run a flow now sees a control plane of primitives rather than one `runFlow`.

**Winner: A, given the stated agent-native direction.** This dimension is the crux of
*why* you'd take on A at all. If the org is not actually committed to agents as a
first-class consumer, much of A's value evaporates and B is the saner scope.

---

## Dimension 8 — Migration cost & risk

**B:** Low. B largely *formalizes the current architecture* — Orchestra stays a flow
runner, YAML stays the input, full hierarchy stays the observation, snapshot-poll stays
the sync. The main net-new work is the clean `MaestroSession` facade + `FlowEvent`
stream + fixing the values/thrown contradiction. This is a refactor + API tidy, not a
rebuild. Ships incrementally.

**A:** High, if taken whole. A touches: (1) promote typed IR to the public contract +
build adapters (medium — the typed model exists, so this is mostly surfacing it);
(2) targeted-observe + `Element` model + LLM projection (medium-high — new device-side
capture scoping + cross-platform Element mapping); (3) errors-as-values + category
taxonomy (medium — mechanical but pervasive, and requires deciding the category for
every existing error); (4) span-tree trace + NDJSON (medium — new emission throughout
Orchestra); (5) event-driven sync + on-device push agent (**high — the big one**,
touches iOS XCTest runner, Android instrumentation, proto/transport). Items 1–4 are
contract changes that can ship behind the existing engine; item 5 is a genuine rebuild.

**Risk framing:** A's risk is *concentrated in item 5* (transport/sync) and to a lesser
degree item 2 (device-side capture). The contract pieces (1, 3, 4) are comparatively
safe and high-value. The honest move is to **unbundle**: A's contract is adoptable at
B-like risk; A's *runtime* (event-driven sync) is the costly, risky part and should be
sequenced separately. B's whole appeal is that it never takes item 5 on — at the cost of
keeping today's flakiness forever.

**Winner: B on raw cost/risk.** This is B's strongest dimension and it should not be
minimized. The mitigation is that ~80% of A's *value* (typed IR, Element model, error
values, span trace) lives in the ~60%-of-cost contract layer, not in the expensive sync
rebuild.

---

## Dimension 9 — Simplicity / Clarity / Flexibility as architecture

**Simplicity (today, single consumer): B wins.** One input (YAML), one verb
(`runFlow`), one event list. Fewer concepts, fewer public types, matches the engine's
grain. If you describe the system in one sentence, B's is shorter and truer to current
code.

**Clarity of the contract: A wins.** A's four dimensions (Inputs / Observation / Errors
/ Trace) are *named, orthogonal, and each fully specified with concrete values.* A
reviewer can reason about each independently. B mixes concerns (validation + transport +
events under "Core's role"), leaves transport/sync unspecified, and has an internal
contradiction (values vs thrown) — so B is *simpler* but less *clear/consistent*.

**Flexibility / extensibility: A wins, clearly.** `schemaVersion` + adapter pattern,
multiple producers, capability negotiation, perceive-act primitives, and a trace that
serves arbitrary consumers all make A composable in ways B's flow-only,
YAML-in/events-out pipe is not. B is a good *pipe*; A is a *platform*. The flip side:
A's flexibility is unused weight if you only ever need the pipe.

**The over-engineering charge against A, fairly stated:** A is over-engineered *if* the
real requirement is "run YAML test suites reliably in CI." For that, B + fixing the
error contradiction + targeted observe is plenty, and A's primitives, capability
handshake, and event-driven sync are speculative generality. A is *right-sized* only if
"agents are a first-class core consumer" is a real, funded requirement. The proposal's
own thesis stands or falls on that premise — which is a product decision, not an
architectural one.

---

## Scorecard

| Dimension | Winner | Confidence | Notes |
|---|---|---|---|
| 1. Inputs / IR | A (if non-YAML producers matter) | High | Typed model already exists; `ref` binding is YAML-impossible |
| 2. Observation | A on types/projection; B on completeness | Medium | Keep A's `Element`+`hittable`+projection AND a `snapshot()` escape hatch |
| 3. Errors | **A, decisively** | Very high | B's values-vs-thrown is a real defect, flagged by B's own reviewer |
| 4. Trace | A on fidelity; B on consumer simplicity | High | A solves B's own open retry/composite question by construction |
| 5. Transport / Sync | A on destination; B lower-risk | Medium-high | A's event-driven sync is the costliest item — decouple it |
| 6. Device / Platform | ~even, lean A for agents | Medium | Combine: B's detection-as-default + A's `capabilities()`; keep B's `Closeable` |
| 7. Agent-nativeness | A (given the stated goal) | High | This is the whole reason to take on A |
| 8. Migration cost/risk | **B** | High | B's strongest dimension; A's value is mostly in its cheaper contract layer |
| 9. Simplicity/Clarity/Flex | B simpler; A clearer + more flexible | High | A right-sized only if agents are a real requirement |

---

## Overall verdict

**Adopt A's contract; stage it through B's shipping shape; treat A's event-driven sync
as a decoupled follow-on.**

A is the better *architecture* on the dimensions that decide long-term cost: the error
model (decisively — B has a genuine contradiction), the trace (it answers B's own open
question), the input IR and agent-native primitives (assuming agents are a real
consumer, which the rearchitecture premise asserts). B is the better *immediate plan* on
exactly one axis that matters a lot in practice: migration cost and risk, because B is
essentially today's system with a tidy facade.

The two are reconcilable, and the reconciliation is the recommendation:

1. **Fix B's error contradiction now, in B's own shape** — unify
   `DeviceDisconnectException`/`JsEvaluationException` into the `CommandFailed`/event
   channel as values with `category` + `retryable`. This is mandatory under either
   direction and is the single highest-value, lowest-cost change.
2. **Surface the typed Command IR as the public contract** and make YAML an adapter
   over it (the typed model already exists in `maestro-orchestra-models`, so the cost is
   surfacing, not building). This unlocks non-YAML producers and `ref` binding.
3. **Adopt A's `Element` model + targeted `observe()` + LLM projection, keep a
   `snapshot()` escape hatch.** Buys agent-readability and `hittable` actionability
   without losing full-hierarchy completeness.
4. **Emit A's span-tree trace, with a "top-level only" filter** so simple/CLI consumers
   get B-like flat ergonomics for free while evals/agents get the tree.
5. **Defer event-driven sync** (the on-device push agent). It is A's most expensive,
   riskiest item, it is *separable* from the contract, and the contract is explicitly
   designed to accept it later. Ship the contract on today's snapshot-poll, then swap.
6. **Combine device handling:** B's platform-detection as the default (`platform`
   optional), plus A's `capabilities()` handshake, plus B's `Closeable` lifecycle.

Net: take A's *type decisions* (they are correct and mostly cheap), take B's
*risk posture and lifecycle ergonomics*, and explicitly sequence A's expensive runtime
rebuild last. The dimension that breaks the tie is **errors** — A is unambiguously right
and B is unambiguously self-contradictory there — and that fix is owed regardless. The
dimension where you must respect B is **migration risk**, and the way to respect it is
to unbundle A's cheap-but-valuable contract from A's costly-and-risky event-driven sync.

> One caveat stated plainly: every "A wins" above is conditioned on agents being a
> genuine first-class consumer of the core. If that premise is false — if Maestro stays
> a YAML-test-suite tool — then A is largely over-engineered and B (plus the error fix
> and targeted observe) is the right and sufficient answer. The architecture choice is
> therefore downstream of a product commitment, and that commitment should be made
> explicitly before A's larger pieces are funded.
