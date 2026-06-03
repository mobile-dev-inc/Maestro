# Worked Example: The Maestro Core Contract in Action

> A single real device interaction, narrated end-to-end, so you can *see* the four
> contract dimensions — Inputs, Observation, Errors, Trace — with concrete type
> shapes and concrete values. The thesis: Maestro's core API is a typed **control
> plane**, not YAML. YAML is one authoring/persistence adapter that parses down to
> the exact same `Command[]` the control plane consumes.

---

## Scenario

An AI agent drives a real app on an iOS simulator to test the sign-in flow. It
connects a session, observes to orient itself, taps **Sign In**, types an email and
password, taps **Submit** (which is briefly disabled behind a spinner), and then
asserts a welcome message. The login *fails* — the server rejected the credentials
and rendered an inline "Invalid password" banner — so the `assertVisible` times out.
The agent reads the structured failure, inspects the near-miss `candidates`, and
correctly concludes its test caught a real bug. We then contrast that with what an
*infra* failure (device disconnect) would have looked like instead.

---

## Dimension 1 — Inputs (Command IR)

The core consumes a typed, versioned, cross-platform **Command IR**: a discriminated
union on `kind`. No strings-as-API, no YAML-as-API.

```ts
type Command =
  | { kind: "launchApp"; appId: string; clearState?: boolean }
  | { kind: "tapOn"; target: Selector }
  | { kind: "inputText"; target: Selector; text: string }
  | { kind: "assertVisible"; target: Selector; timeoutMs?: number }
  | { kind: "swipe"; from: Selector | Point; direction?: Direction; durationMs?: number }
  | { kind: "waitForIdle"; timeoutMs?: number };

interface Flow {
  schemaVersion: 1;            // versioned: the IR can evolve without breaking adapters
  appId?: string;              // default target app for the flow
  commands: Command[];
  config?: FlowConfig;         // retries, default timeouts, env, etc.
}

// A re-resolvable, semantic-first matcher. `ref` targets an element captured by a
// prior observation; everything else is matched fresh at execution time.
interface Selector {
  id?: string;                 // stable native/automation id when available
  role?: Role;                 // ARIA-aligned role
  text?: string | { pattern: string };  // literal or regex
  name?: string;               // accessible name
  nth?: number;                // disambiguate when N match
  ref?: string;                // bind to an Element from an Observation
}

type Role =
  | "button" | "textbox" | "text" | "link" | "checkbox"
  | "image" | "list" | "header" | "switch" | "tab";

type Direction = "up" | "down" | "left" | "right";
interface Point { x: number; y: number }
```

### The scenario's commands, as literal IR values

```ts
const launch: Command   = { kind: "launchApp", appId: "dev.mobile.demo", clearState: true };
const tapSignIn: Command = { kind: "tapOn", target: { ref: "e3" } };               // ref from observe()
const typeEmail: Command = { kind: "inputText", target: { role: "textbox", name: "Email" },
                             text: "ada@example.com" };
const typePwd: Command   = { kind: "inputText", target: { role: "textbox", name: "Password" },
                             text: "hunter2" };
const tapSubmit: Command = { kind: "tapOn", target: { role: "button", text: "Submit" } };
const assertHome: Command = { kind: "assertVisible",
                              target: { role: "text", text: { pattern: "^Welcome, " } },
                              timeoutMs: 5000 };
```

### Proof that YAML is an adapter, not the API

The agent above emits Command IR **directly in TypeScript**. A human authoring the
same test writes Maestro YAML. Both land on an *identical* `Command[]`.

(a) Agent emits IR directly:

```ts
const flow: Flow = {
  schemaVersion: 1,
  appId: "dev.mobile.demo",
  commands: [launch, tapSignIn, typeEmail, typePwd, tapSubmit, assertHome],
};
await session.run(flow);
```

(b) The same flow as Maestro YAML:

```yaml
appId: dev.mobile.demo
---
- launchApp:
    clearState: true
- tapOn:
    role: button
    text: "Sign In"
- inputText:
    target:
      role: textbox
      name: Email
    text: "ada@example.com"
- inputText:
    target:
      role: textbox
      name: Password
    text: "hunter2"
- tapOn:
    role: button
    text: "Submit"
- assertVisible:
    role: text
    text: "^Welcome, "
    timeoutMs: 5000
```

> The YAML adapter is a pure function `parse(yaml) -> Flow`. Its output is
> byte-for-byte the same `Command[]` the agent built by hand (modulo `tapSignIn`,
> which the agent could only express as `{ ref: "e3" }` because it *observed first*;
> the YAML author, having no live observation, expresses the equivalent intent
> semantically as `{ role: "button", text: "Sign In" }`). The control plane neither
> knows nor cares which surface produced the IR.

---

## Dimension 2 — Observation

Perception is built from cheap, *targeted* primitives plus a typed, cross-platform
`Element` model — **not** a full view-hierarchy dump diffed on a timer.

```ts
interface Element {
  ref: string;                 // opaque handle, stable for this observation generation
  role: Role;
  name?: string;
  value?: string;
  state: {
    enabled: boolean;
    focused?: boolean;
    selected?: boolean;
    checked?: boolean;
    editable?: boolean;
  };
  bounds: { x: number; y: number; w: number; h: number };
  visible: boolean;            // present & on-screen
  hittable: boolean;           // visible AND not occluded/disabled — Playwright-style actionability
  scrollable?: boolean;
  nativeId?: string;           // underlying platform id, when present
}

interface Session {
  observe(query: Selector): Promise<Element[]>;   // cheap, targeted — the default
  snapshot(opts?: { maxDepth?: number }): Promise<Element[]>; // depth-bounded full tree, rare
  exists(query: Selector): Promise<boolean>;
  screenshot(): Promise<Screenshot>;
}
```

### A concrete `observe()` call and its result

```ts
const hits = await session.observe({ role: "button", text: "Sign In" });
```

```ts
// hits: Element[]
[
  {
    ref: "e3",
    role: "button",
    name: "Sign In",
    state: { enabled: true, focused: false },
    bounds: { x: 48, y: 612, w: 279, h: 48 },
    visible: true,
    hittable: true,
    nativeId: "auth.signInButton",
  },
]
```

### The compact LLM-facing projection

The same observation, projected into the dense, ref-bearing form an agent reads
cheaply (Playwright-accessibility-tree style). One line per element; refs are the
currency the agent passes back into `tapOn`/`inputText`.

```
- button "Sign In" [ref=e3]
```

A broader `observe({ role: "textbox" })` after tapping Sign In projects to:

```
- textbox "Email" [ref=e7]
- textbox "Password" [ref=e8] (editable)
```

> The host owns *all* selector matching, scoring, and `nth`/disambiguation policy.
> The on-device agent only captures and streams raw accessibility nodes; it never
> decides what "the Sign In button" means.

---

## Dimension 3 — Errors

Outcomes are **values, not exceptions**. Nothing throws across the boundary. Every
command returns a `CommandResult`; failures carry a stable machine `code` and a
`category` that names *who fixes it*.

```ts
type CommandResult =
  | { ok: true;  durationMs: number }
  | { ok: false; error: MaestroError; durationMs: number };

interface MaestroError {
  code: string;                          // stable machine code, e.g. "ELEMENT_NOT_FOUND"
  category: "author" | "test" | "app" | "infra";  // who-fixes-it
  message: string;                       // human-readable, never parse this
  retryable: boolean;
  retryAfterMs?: number;
  selector?: Selector;                   // what we were looking for
  candidates?: Element[];                // near-misses actually on screen
  observationRef?: string;               // pointer into the trace's observation artifacts
  screenshotRef?: string;                // pointer to the captured screenshot artifact
}
```

The category taxonomy:

| category | meaning | who acts |
|----------|---------|----------|
| `author` | the test/flow is malformed (bad selector, impossible step) | flow author |
| `test`   | the app under test behaved wrong vs. expectations | engineer triaging the run |
| `app`    | the app crashed / ANR'd | app developer |
| `infra`  | device/driver/network problem, not the app's fault | CI / platform owner |

### The concrete failure from step 6

`assertVisible "Welcome, ..."` times out. The login genuinely failed: the server
rejected the credentials and the app rendered an inline error banner instead of the
home screen. This is a `test` failure — the app misbehaved relative to the assertion
— and it is **not** retryable, because retrying won't change the server's answer.

```ts
const result: CommandResult = {
  ok: false,
  durationMs: 5021,
  error: {
    code: "ASSERTION_TIMEOUT",
    category: "test",
    message: 'Timed out after 5000ms waiting for text matching /^Welcome, / to be visible.',
    retryable: false,
    selector: { role: "text", text: { pattern: "^Welcome, " } },
    candidates: [
      {
        ref: "e21",
        role: "text",
        name: "Invalid password",
        value: "Invalid password",
        state: { enabled: true },
        bounds: { x: 48, y: 360, w: 279, h: 20 },
        visible: true,
        hittable: true,
      },
      {
        ref: "e22",
        role: "button",
        name: "Submit",
        state: { enabled: true },
        bounds: { x: 48, y: 612, w: 279, h: 48 },
        visible: true,
        hittable: true,
      },
    ],
    observationRef: "obs_4f1c",
    screenshotRef: "shot_4f1c",
  },
};
```

### The agent's self-correction reasoning

Because the failure is *structured*, the agent reasons instead of merely aborting:

```ts
if (!result.ok && result.error.code === "ASSERTION_TIMEOUT") {
  const banner = result.error.candidates?.find(
    (c) => c.role === "text" && /invalid|error|incorrect/i.test(c.name ?? c.value ?? "")
  );
  if (banner) {
    // The expected element never appeared, but a sibling error banner did.
    // Conclusion: this is NOT a flaky locator and NOT an infra issue — the app
    // correctly rejected the credentials. The TEST caught a real bug in the flow's
    // assumptions (or a real product defect). Report category "test" with evidence.
    report({
      verdict: "real-failure",
      reason: `Login rejected: "${banner.value}"`,
      evidence: { observationRef: result.error.observationRef,
                  screenshotRef: result.error.screenshotRef },
    });
  }
}
```

The `candidates` array is the key: a thrown exception or a bare `false` would have
forced the agent to re-`snapshot` and guess. The error *shape* hands it the
near-miss element directly, so it can distinguish "wrong selector" from "app said no."

### Contrast: what an infra failure looks like instead

Had the simulator dropped its connection mid-tap, the *same step* would have returned
a categorically different value — `infra`, with a stable retryable signal:

```ts
const infraResult: CommandResult = {
  ok: false,
  durationMs: 812,
  error: {
    code: "DEVICE_DISCONNECTED",
    category: "infra",
    message: "Lost connection to simulator booted-A1B2 while dispatching tap.",
    retryable: true,
    retryAfterMs: 2000,
    selector: { role: "button", text: "Submit" },
  },
};
```

> Same boundary, same `CommandResult` type, opposite handling. The runner's retry
> policy keys off `retryable` + `retryAfterMs`; the human triage keys off `category`.
> A `test` failure with `retryable:false` is never auto-retried — that would mask a
> real bug. An `infra` failure with `retryable:true` *is* — that's not the app's
> fault. The contract makes "should I retry this?" a typed field, not a heuristic.

---

## Dimension 4 — Trace

The trace is a single source of truth: an append-only **stream of span events** that
both streams live (Studio progress, agent UIs) and serializes to NDJSON on disk. Spans
nest into a tree: `run → flow → command → retry → deviceAction / wait`.

```ts
type SpanKind = "run" | "flow" | "command" | "retry" | "deviceAction" | "wait";

interface Span {
  spanId: string;
  parentSpanId?: string;
  traceId: string;
  kind: SpanKind;
  name: string;
  command?: Command;            // the IR that produced this span (command spans)
  resolvedCommand?: Command;    // post selector-resolution (e.g. ref bound)
  startTs: number;              // epoch ms
  endTs?: number;
  status: "running" | "ok" | "failed" | "skipped" | "warned";
  error?: MaestroError;
  observationRefs?: string[];
  screenshotRefs?: string[];
  source?: { file?: string; line?: number };  // back-pointer to YAML/source when present
}

// The wire/disk format: an append-only event log, NOT whole-span snapshots.
type TraceEvent =
  | { type: "SpanStarted"; span: Span }
  | { type: "SpanEnded"; spanId: string; endTs: number;
      status: Span["status"]; error?: MaestroError }
  | { type: "SpanEvent"; spanId: string; ts: number; name: string;
      attrs?: Record<string, unknown> }   // point-in-time markers (poll ticks, idle pushes)
  | { type: "ArtifactRef"; spanId: string; ref: string;
      kind: "observation" | "screenshot" };
```

### Concrete NDJSON excerpt of *this* run

The interesting slice: the **Submit** tap waits (event-driven) for the spinner to
finish and the button to become hittable, then the **assertVisible** fails after a
retry and poll ticks. Note the nesting via `parentSpanId`, the child `wait` span fed
by a pushed `device.idle` event, the `retry` span, and the `SpanEvent` poll ticks.

```jsonl
{"type":"SpanStarted","span":{"spanId":"s0","traceId":"t1","kind":"run","name":"signin-eval","startTs":1716883200000,"status":"running"}}
{"type":"SpanStarted","span":{"spanId":"s1","parentSpanId":"s0","traceId":"t1","kind":"flow","name":"sign-in","startTs":1716883200010,"status":"running","source":{"file":"signin.yaml","line":1}}}
{"type":"SpanStarted","span":{"spanId":"s2","parentSpanId":"s1","traceId":"t1","kind":"command","name":"tapOn Submit","command":{"kind":"tapOn","target":{"role":"button","text":"Submit"}},"resolvedCommand":{"kind":"tapOn","target":{"ref":"e22"}},"startTs":1716883201000,"status":"running","source":{"file":"signin.yaml","line":18}}}
{"type":"SpanStarted","span":{"spanId":"s3","parentSpanId":"s2","traceId":"t1","kind":"wait","name":"actionable(e22)","startTs":1716883201005,"status":"running"}}
{"type":"SpanEvent","spanId":"s3","ts":1716883201006,"name":"actionability","attrs":{"hittable":false,"reason":"disabled-while-spinner"}}
{"type":"SpanEvent","spanId":"s3","ts":1716883201240,"name":"device.idle","attrs":{"pushed":true}}
{"type":"SpanEvent","spanId":"s3","ts":1716883201241,"name":"actionability","attrs":{"hittable":true}}
{"type":"SpanEnded","spanId":"s3","endTs":1716883201242,"status":"ok"}
{"type":"SpanStarted","span":{"spanId":"s4","parentSpanId":"s2","traceId":"t1","kind":"deviceAction","name":"tap@(187,636)","startTs":1716883201243,"status":"running"}}
{"type":"SpanEnded","spanId":"s4","endTs":1716883201298,"status":"ok"}
{"type":"SpanEnded","spanId":"s2","endTs":1716883201299,"status":"ok"}
{"type":"SpanStarted","span":{"spanId":"s5","parentSpanId":"s1","traceId":"t1","kind":"command","name":"assertVisible /^Welcome, /","command":{"kind":"assertVisible","target":{"role":"text","text":{"pattern":"^Welcome, "}},"timeoutMs":5000},"startTs":1716883201300,"status":"running","source":{"file":"signin.yaml","line":21}}}
{"type":"SpanStarted","span":{"spanId":"s6","parentSpanId":"s5","traceId":"t1","kind":"retry","name":"observe-poll #1","startTs":1716883201301,"status":"running"}}
{"type":"SpanEvent","spanId":"s6","ts":1716883201301,"name":"observe","attrs":{"matches":0}}
{"type":"SpanEvent","spanId":"s6","ts":1716883202310,"name":"observe","attrs":{"matches":0}}
{"type":"SpanEvent","spanId":"s6","ts":1716883203320,"name":"observe","attrs":{"matches":0}}
{"type":"SpanEnded","spanId":"s6","endTs":1716883206301,"status":"failed"}
{"type":"ArtifactRef","spanId":"s5","ref":"obs_4f1c","kind":"observation"}
{"type":"ArtifactRef","spanId":"s5","ref":"shot_4f1c","kind":"screenshot"}
{"type":"SpanEnded","spanId":"s5","endTs":1716883206321,"status":"failed","error":{"code":"ASSERTION_TIMEOUT","category":"test","message":"Timed out after 5000ms waiting for text matching /^Welcome, / to be visible.","retryable":false,"selector":{"role":"text","text":{"pattern":"^Welcome, "}},"observationRef":"obs_4f1c","screenshotRef":"shot_4f1c"}}
{"type":"SpanEnded","spanId":"s1","endTs":1716883206322,"status":"failed"}
{"type":"SpanEnded","spanId":"s0","endTs":1716883206323,"status":"failed"}
```

### Two consumers, one stream

- **Live (Studio progress bar):** subscribes to the event stream and renders only
  *top-level* spans — `run`, `flow`, and each `command` (`s0`, `s1`, `s2`, `s5`).
  It shows "tapOn Submit ✓ / assertVisible ✗" without ever materializing the `wait`,
  `retry`, or `deviceAction` children. Cheap, glanceable, real-time.
- **Agent / eval (post-mortem or live drill-down):** walks `parentSpanId` into the
  children — reads `s3`'s `device.idle` push to confirm sync was event-driven (not a
  sleep), reads `s6`'s three `observe` poll ticks (all `matches:0`) to confirm the
  element genuinely never appeared, and follows the `ArtifactRef`s (`obs_4f1c`,
  `shot_4f1c`) to the exact bytes behind the failure's `observationRef`/`screenshotRef`.

Because the live wire format and the on-disk NDJSON are the *same* event stream,
there is no second serialization path to drift out of sync.

---

## End-to-end sequence

The whole agent loop, in one cohesive block. Inline tags mark which dimension each
call exercises.

```ts
import { maestro } from "@maestro/core";

async function runSignInEval() {
  // [SESSION] platform is explicit; capabilities negotiated in one line.
  const session = await maestro.connect({ deviceId: "booted-A1B2", platform: "ios" });
  const caps = await session.capabilities();  // { observe: true, eventDrivenSync: true, ... }

  // [TRACE] subscribe to the live span-event stream (same stream that hits disk).
  session.trace.subscribe((evt) => onTraceEvent(evt));

  // [INPUT] launch via Command IR.
  await session.run({ kind: "launchApp", appId: "dev.mobile.demo", clearState: true });

  // [OBSERVATION] cheap targeted observe to orient; get a ref, not a hierarchy dump.
  const [signIn] = await session.observe({ role: "button", text: "Sign In" });
  //   projection seen by the model: - button "Sign In" [ref=e3]

  // [INPUT] act by ref. [TRACE] internally awaits the device-pushed "idle" event,
  //   no fixed sleep; the wait span (s3) records the actionability transition.
  let r = await session.run({ kind: "tapOn", target: { ref: signIn.ref } });  // [ERROR] r is a value

  // [OBSERVATION] find the fields, then [INPUT] type into them.
  const [email] = await session.observe({ role: "textbox", name: "Email" });
  const [pwd]   = await session.observe({ role: "textbox", name: "Password" });
  await session.run({ kind: "inputText", target: { ref: email.ref }, text: "ada@example.com" });
  await session.run({ kind: "inputText", target: { ref: pwd.ref },   text: "hunter2" });

  // [INPUT + sync] Submit is briefly !hittable behind a spinner; actionability
  //   checks (driven by pushed events) wait for hittable:true before tapping.
  await session.run({ kind: "tapOn", target: { role: "button", text: "Submit" } });

  // [INPUT] the assertion. [ERROR] returns a CommandResult — it does NOT throw.
  r = await session.run({
    kind: "assertVisible",
    target: { role: "text", text: { pattern: "^Welcome, " } },
    timeoutMs: 5000,
  });

  // [ERROR] handle the failure as a value; reason over its structure.
  if (!r.ok) {
    const e = r.error;
    if (e.category === "infra" && e.retryable) {
      await sleep(e.retryAfterMs ?? 1000);
      return runSignInEval();                       // infra: safe to retry
    }
    // test/app: don't retry — inspect candidates to explain WHY.
    const banner = e.candidates?.find(
      (c) => c.role === "text" && /invalid|error/i.test(c.value ?? c.name ?? "")
    );
    report({
      verdict: banner ? "real-failure" : "investigate",
      code: e.code,
      category: e.category,                          // "test"
      reason: banner ? `Login rejected: "${banner.value}"` : e.message,
      observationRef: e.observationRef,              // obs_4f1c
      screenshotRef: e.screenshotRef,                // shot_4f1c
    });
    return;
  }

  report({ verdict: "pass" });
}
```

---

## Takeaway

The four dimensions compose into a closed loop: **Observation** yields refs and an
LLM-readable projection that feed **Inputs** (Command IR); executing those inputs
produces **Errors** as typed `CommandResult` values whose `category`/`candidates`
let a caller reason rather than abort; and every step of that loop emits a **Trace**
span into one append-only stream that is simultaneously the live UI feed and the
on-disk record. Crucially, the *same* contract serves two very different surfaces
from one core: an interactive agent uses the perceive-act primitives
(`observe → run → observe`) in a tight loop, while a persisted YAML test is just a
`Flow` that the YAML adapter parses into the identical `Command[]` and hands to a
batch runner. Same control plane, same Element model, same error taxonomy, same
trace — only the authoring surface differs. YAML stops being the API and becomes
what it always should have been: one adapter among several.
