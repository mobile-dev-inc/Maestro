# Review: Command IR, Errors, and Contract Completeness

Scope: the INPUTS (Command IR), ERRORS, and CONTRACT-COMPLETENESS of the proposed
rearchitecture (`worked-example-core-contract.md`, `contract-comparison.md`). Grounded
in Maestro's current code, PR rationale, and the cross-framework consensus.

---

## 1. Maestro today (with PR rationale)

### 1.1 The command model is already a typed IR — but a peculiar one

The typed Command IR the proposal wants *already exists* in `maestro-orchestra-models`.
The proposal's "YAML is one adapter" claim is therefore ~60% true in the code today:
YAML parses (`YamlCommandReader` → `MaestroFlowParser`) into `List<MaestroCommand>`, and
`Orchestra` consumes that typed list. The gap is shape and surfacing, not existence.

Three structural facts about today's model that bear directly on the proposal:

1. **`MaestroCommand` is an "all-nullable bag," not a discriminated union.**
   `maestro-orchestra-models/.../MaestroCommand.kt` is a data class with ~45 nullable
   `*Command` fields, exactly one of which is non-null. `asCommand()` is a 45-arm `when`
   that returns the first non-null. The header comment says this exists because
   "*The Mobile.dev platform uses this class in the backend and hence the custom
   serialization logic. The earlier implementation ... had a nullable field for each
   command.*" So the bag shape is a **serialization artifact for the cloud backend**, not
   a domain choice. The actual discriminated union is the inner `sealed interface Command`
   (`Commands.kt`). The proposal's `{ kind: "..." }` discriminated union is strictly
   better than the bag and is *also* better than today's Kotlin `sealed interface` for the
   wire, because `kind` is an explicit, versionable tag rather than an implicit class name.
   **Recommendation: keep the inner `sealed interface Command` as the in-process IR; make
   the *wire* form a tagged union (`kind`), and delete the nullable-bag once the backend
   serialization is migrated.** The bag is the single ugliest thing the proposal cleanly
   removes.

2. **`evaluateScripts(jsEngine)` is baked into every `Command` and every value object**
   (`ElementSelector`, `Condition`, `MaestroConfig`). This is the proposal's biggest
   *unstated* collision.
   - **PR rationale (validated):** introduced in **#427 "Inline Javascript"**
     (commit `1cea268a`, Nov 2022) and extended by **#428/#435/#438/#446**. The design:
     command fields hold **raw, un-interpolated strings** (e.g. `text = "${user.email}"`),
     and `evaluateScripts` walks the command at execution time, calling
     `Env.evaluateScripts` which regex-replaces `${...}` by evaluating the body against a
     live `JsEngine` (today GraalJS; Rhino removed). `Orchestra.executeCommands` calls
     `command.evaluateScripts(jsEngine)` immediately before dispatch (Orchestra.kt:254).
   - **Why it's load-bearing:** Maestro variables/conditions/`runScript` are *Turing-complete
     JS evaluated lazily, mid-flow*, against state mutated by prior commands (`copyTextFrom`,
     `evalScript`, `extractTextWithAI` all write JS vars). A command is therefore **not a
     pure value** — it is a thunk over a JS environment. The IR you serialize is the
     *pre-evaluation* IR; the IR you execute is the *post-evaluation* IR.
   - **Collision with the proposal:** the proposal's `Command` literals (`text:
     "ada@example.com"`) are fully-resolved values, and its `Trace.command` /
     `resolvedCommand` split *only* models selector-ref binding, not string interpolation.
     The proposal has no concept of a JS environment, `runScript`, `evalScript`, or
     `${...}` interpolation **anywhere**. This is a real completeness gap (see §4), and it
     is the deepest reason "YAML is just an adapter" is only partly true: the adapter
     emits *thunks*, and the runtime owns a JS engine the contract never mentions.

3. **`CompositeCommand`** (`Commands.kt:61`) — `RunFlowCommand`, `RepeatCommand`,
   `RetryCommand` carry `subCommands(): List<MaestroCommand>` and `config(): MaestroConfig?`.
   - **PR rationale (validated):** `CompositeCommand` / `runFlow` came from **#245
     "Conditional flow execution"** (commit `1e1fb17b`, Sep 2022). The author explicitly
     pivoted from a dedicated `if` to "*Switching to runFlow command instead*" — i.e.
     conditionals were folded into a composite-with-`condition` rather than given their own
     node. This is why there is no first-class `if`/`else` in the IR: it's
     `runFlow{condition}` + `repeat{condition,times}` + `retry{maxRetries}`.
   - **Consequence for the proposal:** the proposal's flat `Command[]` has **no composite
     variant at all** — no `runFlow`, `repeat`, `retry`, or `condition`. That is the single
     biggest IR completeness gap (see §4.2). Today these are first-class and heavily used.

### 1.2 The error hierarchy and the values-vs-thrown split (real, in the code)

`maestro-client/.../Errors.kt`: `sealed class MaestroException` (origin: `Conductor →
Maestro` #33, the very first import) with subclasses `UnableToLaunchApp`,
`UnableToClearState`, `UnableToSetPermissions`, `AppCrash`, `DriverTimeout`,
`AssertionFailure` (+ `ElementNotFound`), `CloudApiKeyNotAvailable`,
`DestinationIsNotWritable`, `UnableToCopyTextFromElement`, `InvalidCommand`,
`HideKeyboardFailure`, `NoRootAccess`, `UnsupportedJavaVersion`, `MissingAppleTeamId`,
`IOSDeviceDriverSetupException`. A *separate* sealed hierarchy
`MaestroDriverStartupException` (`AndroidDriverTimeoutException`,
`AndroidInstrumentationSetupFailure`) covers startup. iOS transport has *yet another*
hierarchy: `maestro-utils/.../network/Errors.kt` `XCUITestServerError`
(`UnknownFailure`, `NetworkError`, `AppCrash`, `OperationTimeout`, `BadRequest`,
`Unreachable`) — note **two** `AppCrash` types and an iOS-only `Unreachable` transport
latch. JS failures are a fourth family: `JsEvaluationException(JsScriptError)`
(`maestro-client/.../js/JsScriptError.kt`), deliberately detached from live Polyglot
internals so it survives serialization.

**Everything is thrown, not returned.** `Orchestra.executeCommands` (Orchestra.kt:271-298)
wraps each `executeCommand` in try/catch: a `MaestroException` on an `optional` command is
re-thrown as `CommandWarned`; otherwise it propagates to `onCommandFailed(index, command,
e): ErrorResolution`, a **callback** the caller supplies (default `{ throw e }`). So the
"error model" today is: throw a `MaestroException` subtype, let a host callback decide
`FAIL`/`CONTINUE`. There is **no stable machine code, no `category`, no `retryable`, no
near-miss `candidates`** on any error. `AssertionFailure`/`ElementNotFound` *do* carry
`hierarchyRoot: TreeNode` + `debugMessage` (the raw hierarchy at failure) — that is the
one piece of structured evidence today, and it is the full hierarchy, not near-misses.

**iOS/Android divergence (note as requested):** the command model and `MaestroException`
are shared/cross-platform. Divergence lives *below* the command layer: Android has
`MaestroDriverStartupException` + instrumentation failures; iOS has the entire
`XCUITestServerError` transport family with the `Unreachable` fail-fast latch. So the
proposal's "uniform `CommandResult`" would have to *flatten three platform-divergent
exception families into one taxonomy* — feasible and desirable, but the mapping is
non-trivial and is where most of the error-migration cost actually sits.

### 1.3 The agent surface today proves the proposal's diagnosis

The closest thing to an agent consumer that ships today is the MCP `RunTool`
(`maestro-cli/.../mcp/tools/RunTool.kt`). Its execution path is literally:
`try { ... } catch (e: Exception) { errorResult("Failed to run flow: ${e.message}") }`.
A structured failure (assertion timeout with a hierarchy) is collapsed to a **string**
the moment it reaches the agent. This is concrete evidence for the proposal's core claim:
today an agent cannot reason over a failure's structure because the structure is destroyed
at the boundary. `category`/`candidates`/`retryable` would survive where `e.message` does
not.

### 1.4 The `appId` smell the proposal accidentally fixes

`MaestroConfig.kt` carries an explicit confession:
`// Note: The appId config is only a yaml concept for now. It'll be a larger migration to
get to a point where appId is part of MaestroConfig (and factored out of MaestroCommands -
eg: LaunchAppCommand).` Today `appId` is duplicated across `LaunchAppCommand`,
`ClearStateCommand`, `StopAppCommand`, `KillAppCommand`, `SetPermissionsCommand`. The
proposal's `Flow{appId?}` + `launchApp{appId}` is the migration that comment asks for. Good
— but it under-specifies multi-app flows (see §4.1): today every app-lifecycle command
takes its own `appId` precisely so a flow can drive two apps.

---

## 2. PR-archaeology summary (load-bearing choices)

| Choice | Origin | Why (validated) | Verdict |
|---|---|---|---|
| `evaluateScripts(jsEngine)` on every command | #427 Inline Javascript (`1cea268a`), +#428/#435/#446 | Commands hold raw `${...}` strings; lazy JS interpolation at execute-time against live, flow-mutated JS state. Commands are thunks, not values. | Load-bearing and correct for its goal; **the proposal does not model it at all** (§4.4). |
| `CompositeCommand` + `runFlow{condition}` instead of `if` | #245 Conditional flow (`1e1fb17b`) | Author explicitly chose composite-with-condition over a dedicated conditional node. | Load-bearing; **proposal drops composites entirely** (§4.2). |
| `MaestroException` sealed thrown hierarchy | #33 Conductor→Maestro (original) | Idiomatic Kotlin; predates any agent/stream consumer. | Legacy-reasonable, but the "throw + host callback `ErrorResolution`" model is exactly what the proposal replaces with values; the win is real (§3). |
| `MaestroCommand` nullable bag | #33 original; comment cites cloud backend serialization | Backend custom (de)serialization; "earlier impl had a nullable field per command." | A serialization artifact, not a domain truth. Proposal's tagged union is strictly better. |

No choice here is *only* legacy: each had a concrete driver. But two of them
(`evaluateScripts`, composites) are load-bearing facts the proposal currently ignores.

---

## 3. Other frameworks — command and error consensus

**Appium / Selenium (W3C WebDriver):** commands are a fixed REST/JSON endpoint set
(findElement, click, sendKeys, etc.). Errors are a **standardized closed taxonomy** keyed
by a string code + 4xx/5xx status: `no such element` (404), `stale element reference`,
`element not interactable`, `element click intercepted`, `invalid selector`, `timeout`,
`no such window`, `invalid session id`, `unknown error`, `session not created`. This is the
de-facto industry error vocabulary. The proposal's `ELEMENT_NOT_FOUND` / `ASSERTION_TIMEOUT`
/ `DEVICE_DISCONNECTED` / `STALE_REF` should **align names to W3C where they overlap**
(`STALE_REF` ≈ `stale element reference`, `ELEMENT_NOT_FOUND` ≈ `no such element`) so the
codes are recognizable. Crucially: **WebDriver has no `category` (who-fixes-it) and no
`retryable` field.** Those are the proposal's genuine additions beyond the consensus — and
they are the riskiest (§5.3), because the rest of the industry deliberately stayed at
"facts only."

**Playwright:** auto-waiting **actionability** checks (visible, stable, enabled, receives
events, not obscured) before every action — this is exactly the proposal's `hittable` and
the `wait`-span model. Playwright throws `TimeoutError` with a rich message but, notably,
also stays facts-only (no who-fixes-it). Its locators are **lazy and re-resolved on every
use** — this is the closest prior art to the proposal's re-resolving `Selector` and is a
strong validation of that choice (and an implicit warning that `ref` handles, which
Playwright deliberately does *not* expose as durable, are the riskier part — see §5.2).

**Espresso / XCUITest:** Espresso `ViewMatchers`/`ViewActions` with synchronization via the
idling-resource/main-looper model (event-driven idle — validates the proposal's
`device.idle` push). Errors: `NoMatchingViewException`,
`AmbiguousViewMatcherException` (note: a *dedicated* "too many matches" error — the proposal
folds this into `candidates`/`nth` but has no explicit `AMBIGUOUS_MATCH` code, worth
adding). XCUITest: `XCUIElementQuery` is **lazy/re-resolved** (again validates
re-resolution), `waitForExistence(timeout)`, and failures are `XCTIssue`s.

**Consensus error taxonomy:** not-found, stale/detached, not-interactable/obscured,
ambiguous-match, timeout, invalid-selector, session/transport-dead, plus app-crash. The
proposal covers most; **add `AMBIGUOUS_MATCH` and `INVALID_SELECTOR`**, and **align spellings
to W3C**. `category`/`retryable` are net-new and not industry-blessed — keep, but constrain
(§5.3).

---

## 4. Contract completeness — what the 4 dimensions miss

The proposal's four dimensions (Inputs / Observation / Errors / Trace) are the right
*runtime* axes. But "Inputs = a flat `Command[]`" silently drops several first-class
concerns that Maestro models today. Below: each gap and where it should live.

### 4.1 Device & app lifecycle — PARTIAL. Belongs: split between Session and Command IR.
The worked example shows only `launchApp{appId,clearState}`. Today's IR also has
`stopApp`, `killApp`, `clearState`, `clearKeychain`, `setPermissions`, `openLink`
(deep-links, with `autoVerify`/`browser`), `addMedia`, `setLocation`/`travel`,
`setOrientation`, `setAirplaneMode`/`toggleAirplaneMode`, `startRecording`/`stopRecording`.
- **Permissions** are both a `launchApp.permissions` map *and* a standalone
  `SetPermissionsCommand` — first-class, and absent from the proposal.
- **Deep-links** (`openLink`) — absent.
- **Multi-app**: every lifecycle command carries its own `appId` *by design* so one flow
  can drive two apps; `Flow{appId?}` as a single default is necessary but **not sufficient** —
  keep per-command `appId` override.
- **Where it belongs:** physical-device/session concerns (acquire device, install/uninstall
  app, grant OS permissions, set location/orientation/airplane, screen-record) belong on
  **`Session` as methods/capabilities**, NOT in the per-flow `Command[]` — they are
  environment setup, not test steps, and they are where platform divergence is worst. But
  *in-flow, mid-test* lifecycle (relaunch with clearState between sub-flows, deep-link
  navigation as a step) must remain **Commands**. The proposal needs an explicit line:
  "lifecycle that sets up the world = Session; lifecycle that is a test step = Command."
  Right now it has neither the Session methods nor most of the Commands.

### 4.2 Composite & control-flow — MISSING ENTIRELY. Belongs: first-class IR nodes. **(biggest gap)**
The proposal's `Command[]` has no `runFlow`, `repeat`, `retry`, conditional, or sub-flow.
Maestro's `CompositeCommand` (with `subCommands()` + per-composite `config()`), `Condition`
(platform / visible / notVisible / scriptCondition), `RepeatCommand{times,condition}`,
`RetryCommand{maxRetries}`, and `RunFlowCommand{commands,condition,config}` are all
first-class and pervasive. A flat array cannot represent them. This is **the** completeness
gap, and the contract-comparison doc *itself* flagged it from the Trace angle ("how to
represent intermediate events for retries/composites") without realizing the *Input* IR has
the same hole. **Where it belongs: the Command discriminated union must include recursive
composite variants** — e.g. `{kind:"runFlow", commands:Command[], when?:Condition,
config?}`, `{kind:"repeat", commands:Command[], times?, while?:Condition}`,
`{kind:"retry", commands:Command[], maxAttempts?}`, and a `Condition` type
(`{visible?|notVisible?|platform?|script?}`). The Trace span tree (`run→flow→command→retry`)
is *already designed* for this recursion; the Input IR must match it. Without composites the
IR cannot express any real Maestro flow.

### 4.3 Assertions as their own concern — UNDER-MODELED. Belongs: a distinct result shape, not error-shaped.
The proposal treats a failed `assertVisible` as a `CommandResult.error` with
`category:"test"`. That conflates two things Maestro keeps closer together than the proposal
admits and that frameworks separate: (a) an **action** that couldn't be performed
(not-found/not-hittable — operational) vs (b) an **assertion** that evaluated to false (the
app is in a state the test forbids — a verdict). Today: `AssertConditionCommand` with a
`Condition`, `assertTrue` (`scriptCondition`), `assertNoDefectsWithAI`, `assertWithAI`,
`extractTextWithAI`, `assertScreenshot` (visual-diff with threshold). Several of these
**produce values, not just pass/fail** (`extractText*` writes a variable; `copyTextFrom`
captures text). The proposal's `CommandResult = ok|{error}` has **no slot for a produced
value** and no notion of a soft/negative assertion (`assertNotVisible` succeeds *because*
something is absent — `candidates` is meaningless there). **Where it belongs:** make
assertion outcome first-class — `CommandResult` needs an `ok` payload that can carry an
**extracted value / captured text / boolean verdict / diff score**, and `assertVisible`-fail
should arguably be `{ok:true, satisfied:false}` (a measured fact) rather than `{ok:false,
error}` (a failure to execute). The "is a false assertion an error or a result?" question is
unanswered and is a genuine design fork.

### 4.4 Secrets / auth — MISSING (and absent today too). Belongs: Session/env layer with redaction in Trace + Errors.
Maestro today has **no secrets primitive at all** — passwords are plain `${...}` strings in
env, interpolated into `inputText.text`, and (critically) those resolved values land in the
Trace's `resolvedCommand`, in `CommandMetadata.evaluatedCommand`, and in error
`hierarchyRoot`. The worked example happily puts `text:"hunter2"` straight in the IR and in
`resolvedCommand` in the NDJSON. **The proposal's "Trace is the single source of truth,
serialized to NDJSON on disk" makes this WORSE: it writes resolved secrets to disk by
construction.** **Where it belongs:** (1) a `secret`/`masked` marker on env vars at the
Session/env layer; (2) the IR carries a *reference* (`{secretRef:"PASSWORD"}`) not the
literal; (3) Trace/Error serialization MUST redact resolved secret values in
`resolvedCommand`, `candidates`, `value`, and `screenshotRef`. This is a security gap the
new on-disk-NDJSON design actively introduces and must address before shipping.

### 4.5 Env / variables / parameterization — MISSING from the contract. Belongs: explicit Env dimension feeding the JS engine.
This is the §1.1-#2 collision restated as a completeness gap. `--env`, flow `env:`,
`defineVariables`, `MAESTRO_*` injected vars (`Env.withDefaultEnvVars`,
`withInjectedShellEnvVars`), `runScript`/`evalScript`, and `${...}` interpolation are how
every real flow is parameterized. The proposal's `Flow{config?}` hand-waves "env" in a
comment and **never models the JS engine, variables, or interpolation**. Yet `Selector.text`,
`inputText.text`, conditions, and timeouts are all routinely `${...}` expressions today.
**Where it belongs:** a first-class **Env/Runtime context** that is (a) an input alongside
`Command[]`, (b) the thing `Command.evaluateScripts` resolves against, and (c) mutated by
`runScript`/`copyText`/`extractText` mid-flow. The contract must decide: does the IR carry
*pre-* or *post-*interpolation values? Today it's pre (thunks). The proposal assumes post
(literals) — which structurally cannot express a flow whose `text` depends on a value
computed at step 3. **This is the second-biggest gap after composites.**

### 4.6 Session / device acquisition — PARTIAL. Belongs: Session, with capabilities.
The proposal's `connect({deviceId,platform})` + `capabilities()` is good and better than
today's magic platform-detection-from-deviceId. But it omits: device *acquisition/booting*
(today the CLI boots/selects simulators and emulators), app **install/uninstall**, the
`Closeable`/`.use{}` lifecycle (the comparison doc correctly credits B for this), artifacts
dir, flow-level timeout, and **sharding/parallelism** (`MAESTRO_SHARD_*` are injected env;
multiple devices run shards). **Where it belongs:** all on `Session`/its factory. The
contract should name a fifth runtime concern explicitly: **session/device lifecycle &
acquisition** (connect, capabilities, install, close, artifactsDir, shard identity).

### 4.7 Smaller omissions (note, lower priority)
`label`/`optional` per command (today every `Command` has `label` + `optional`; `optional`
turns a failure into a *warning* — the proposal has no warned/optional concept though the
Trace `status` includes `"warned"`/`"skipped"`); `waitToSettleTimeoutMs` per command;
`onFlowStart`/`onFlowComplete` hooks (`MaestroConfig`); visual `assertScreenshot` thresholds;
AI commands (`assertWithAI`, `assertNoDefectsWithAI`, `extractTextWithAI`) which return
defects/values and have their own optionality semantics.

---

## 5. Honest critique

### 5.1 IR shape
The discriminated union + `schemaVersion` is correct and an unambiguous upgrade over the
nullable bag. But as drawn it is **incomplete to the point of not being able to express a
real flow**: no composites (§4.2), no env/interpolation (§4.5), no `optional`/`label`, no
deep-link/permissions/lifecycle (§4.1). The worked example works only because it is a
straight-line happy path. Before this IR can claim to subsume YAML it must round-trip an
existing non-trivial flow (one with `runFlow`, `repeat`, `${var}`, `runScript`,
`assertNotVisible`, `setPermissions`). I'd require that round-trip as the acceptance test.

### 5.2 `ref` / STALE_REF under re-render churn
Re-resolving semantic selectors (Playwright/XCUITest lazy locators) is the right default and
well-validated. The concern is the **durable `ref` handle**. Playwright pointedly does *not*
hand out persistent element handles as the primary API precisely because the DOM/AX tree
churns; it re-resolves locators instead. The proposal's `ref` is captured by one `observe()`
and replayed into a later `run({ref})`. Under RN/Compose/SwiftUI re-render churn (the exact
workloads where Maestro's hierarchy is most volatile), the element backing `ref:"e3"` is
frequently torn down and recreated between observe and act, even when "the same button" is
still on screen. `STALE_REF` then fires constantly, and the agent's only recourse is to
re-`observe` — i.e. the `ref` bought nothing over just re-resolving a semantic selector. **My
read: `ref` is valuable *within a single observe→act tick* (the tight agent loop) and a
liability if treated as durable across re-renders.** The contract should (a) scope `ref`
lifetime to the current "observation generation" explicitly (the Element comment says
"stable for this observation generation" — make that a hard, enforced boundary), and (b)
make `STALE_REF` carry the original semantic selector so re-resolution is automatic, not an
agent round-trip. As specified, STALE_REF is under-defined about *when* a ref expires and
will surprise.

### 5.3 facts-not-verdicts / `category` / `retryable`
This is the proposal's boldest and least industry-supported claim (WebDriver, Playwright,
Espresso all stay facts-only). My assessment:
- **`retryable` is cleanly determinable only for a narrow set.** Transport-dead
  (`DEVICE_DISCONNECTED`, iOS `Unreachable` latch, `NetworkError`) is genuinely
  retry-safe → `retryable:true` is a fact. `ASSERTION_TIMEOUT` from a real app-state is
  genuinely not → `false` is a fact. But the **large middle is not determinable**:
  `ELEMENT_NOT_FOUND` after a tap might be a slow render (retry helps) or a wrong selector
  (retry never helps) — the core cannot know which. The doc's own infra example is the easy
  case. **Recommendation: make `retryable` a nullable/tri-state (`true|false|unknown`) and
  forbid the core from guessing** — emit `unknown` and let the runner's policy decide. A
  boolean here is a verdict masquerading as a fact, which violates the doc's own thesis.
- **`category` is cleanly determinable for `app` (crash/ANR — unambiguous) and `infra`
  (transport — unambiguous).** It is NOT cleanly determinable between `author` (bad selector)
  and `test` (app misbehaved): a timed-out `assertVisible` could be either, and the core
  cannot tell — only the `candidates` evidence lets a *downstream* consumer reach that
  verdict (which is exactly what the worked example's agent does). So the core assigning
  `category:"test"` to the login failure **is the core rendering a verdict it admits it
  can't know** — internally contradictory with facts-not-verdicts. **Recommendation:** the
  core emits `category` ONLY for the determinable cases (`app`, `infra`) and emits the
  *evidence* (`candidates`, `observationRef`, `screenshotRef`) for the rest, leaving
  `author`-vs-`test` to the consumer. Rename the field to reflect this — e.g. `fault?:
  "app"|"infra"` (omitted when not determinable) — so its presence *is* the determinability
  signal. This makes the model honest and is a small change.
- **The operational-vs-contract-violation line IS workable and should be stated explicitly**
  (the comparison doc gets this right and the worked example doesn't): *expected operational
  outcomes* (not-found, not-hittable, assertion-false, crash, disconnect, timeout, JS-throw)
  are **values**; *programming/contract violations* (null session, illegal argument, internal
  invariant) **throw**. The worked example's "nothing throws across the boundary" is too
  absolute and the comparison doc already corrects it — adopt the corrected line in the
  primary doc. Pre-execution author errors (malformed flow / parse / schema) throwing is
  correct and matches today's `SyntaxError`/`ValidationError`/`InvalidCommand` flow.

### 5.4 The values migration is bigger than the doc implies
§1.2 shows **four** divergent exception families (`MaestroException`,
`MaestroDriverStartupException`, `XCUITestServerError`, `JsEvaluationException`) plus the
`onCommandFailed → ErrorResolution` callback woven through `Orchestra` and every consumer.
Converting to values is "mechanical but pervasive" (comparison doc) — accurate — but the
*taxonomy mapping* across three platform-divergent families is the real work, and assigning
a stable `code` + (determinable) `fault` to each existing throw site is a line-by-line audit.
Budget it as such.

---

## 6. Recommendation

**Adopt the typed discriminated-union IR and errors-as-values — both are clear wins over the
nullable bag and the four thrown exception families — but the contract as drawn is not yet
complete enough to subsume Maestro.** Required additions before it can:

1. **Composites in the IR** (`runFlow`/`repeat`/`retry`/`Condition`), recursive to match the
   already-recursive Trace span tree. *Non-negotiable; without it the IR can't express a real
   flow.* (§4.2)
2. **A first-class Env/Runtime dimension** + a decision that the IR carries
   **pre-interpolation thunks** resolved against a JS engine (as today), or an explicit story
   for why post-interpolation literals are acceptable (they are not, for any data-dependent
   flow). Model `runScript`/`evalScript`/`${...}`. (§4.5, §1.1-#2)
3. **Lifecycle split:** Session methods for world-setup (acquire/install/permissions/
   location/orientation/record) vs Commands for in-flow lifecycle (relaunch/clearState/
   deep-link). Keep per-command `appId` override for multi-app. (§4.1, §4.6)
4. **Assertions as a distinct outcome shape:** `CommandResult.ok` must carry produced values
   (extractText/copyText/diff-score/verdict); decide whether a false assertion is
   `{ok:false,error}` or `{ok:true,satisfied:false}`. (§4.3)
5. **Secrets:** `secretRef` in IR + mandatory redaction in Trace/Error serialization — the
   on-disk-NDJSON design introduces a plaintext-secret-to-disk regression that must be closed.
   (§4.4)
6. **Error model honesty:** `fault` (≈category) emitted ONLY when determinable (`app`/`infra`);
   `retryable` as tri-state `true|false|unknown`; align codes to W3C
   (`STALE_REF`≈stale-element, `ELEMENT_NOT_FOUND`≈no-such-element); add `AMBIGUOUS_MATCH`
   and `INVALID_SELECTOR`; state the operational-vs-violation throw line explicitly. (§3, §5.3)
7. **`ref` lifetime:** hard-scope to the observation generation; carry the originating
   selector in `STALE_REF` so re-resolution is automatic. (§5.2)
8. Carry `optional`/`label` per command (warned/skipped already exist in Trace `status` but
   have no Input-side representation). (§4.7)

### Completeness-gap list (where each belongs)

| Gap | Status today | Belongs in | Severity |
|---|---|---|---|
| **Composite & control-flow** (runFlow/repeat/retry/conditionals/sub-flows) | first-class (`CompositeCommand`, `Condition`) | **Command IR** (recursive variants) | **Highest** |
| **Env / variables / interpolation / runScript** | first-class (`evaluateScripts`+JsEngine, `defineVariables`) | **new Env/Runtime dimension** feeding command resolution | **Highest** |
| Assertions as their own concern (+ produced values) | `AssertCondition`, `extractText`, `assertScreenshot`, AI asserts | richer **CommandResult.ok** payload + satisfied/verdict shape | High |
| Secrets / auth (redaction) | none (plaintext `${...}`) | **Session/Env** marker + mandatory **Trace/Error redaction** | High (security) |
| Device & app lifecycle (install/permissions/clearState/deep-link/media/location/orientation/recording) | first-class commands + `launchApp.permissions` | **split**: Session (world-setup) vs Command (in-flow) | High |
| Session / device acquisition (boot/install/close/`.use{}`/artifactsDir/shards/caps) | CLI + magic-detect platform | **Session** factory & methods (+ `capabilities()`) | Medium |
| Multi-app (`appId` per command) | per-command `appId` by design | per-command `appId` override + `Flow.appId` default | Medium |
| `optional`/`label`/warned-skipped on inputs | every command | per-command IR fields | Medium |
| `AMBIGUOUS_MATCH` / `INVALID_SELECTOR` codes | `AmbiguousView`(competitors); `nth`/`index` here | Error taxonomy | Low |
