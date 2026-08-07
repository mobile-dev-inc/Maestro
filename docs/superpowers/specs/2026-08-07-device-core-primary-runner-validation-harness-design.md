# Spec — device-core as primary runner: the validation harness

> **Shape: spec doc.** The scaffolding we build *before* the real-flow validation — the enabling
> architecture, the differential instrument, and the small known fixes device-core needs to run a
> flow at all. Written 2026-08-07. Status: draft, pre-approval. This does **not** build device-core
> capabilities (the specific command sequence + the customer flows that exercise them arrive
> separately). It builds the harness those flows run through. Dual tenancy / co-residence is
> abandoned (PRs #3487, device-core #88); device-core runs sole-tenant here.

## Context & goal

The dual-tenancy prototype proved one `assertVisible` could be served by device-core's `inspect()`
while legacy Maestro stayed co-resident and decided everything else. That's a far cry from proving
device-core can *replace* the device layer. The gap: a real flow keeps transports open across many
commands, chains acts through screen transitions, and hits edge cases that only surface mid-run —
none of which a single routed assert touches.

The goal of this work is **validation, not production rollout**: run real, complex customer flows
with device-core as the primary runner and measure whether it can own a whole flow — verdict,
element selection, and timing — the same way legacy does. The output is conviction (or a ranked list
of where it falls down), not a shipping feature.

The key realization that shapes everything below: **the prototype's per-command Orchestra routing is
a good production shape and a poor validation instrument.** When a command is routed inside
Orchestra, legacy's find-loop and `waitForAppToSettle` still run *around* it — so a green routed flow
proves device-core can answer a query inside legacy's safety net, not that it can own a flow. To
validate, device-core has to run **naked**: no legacy synchronization wrapping it. That requirement
is what drives the architecture.

## The decision

**Refactor Orchestra into a thin command router over a pluggable execution backend. Select the
backend per-run.**

- **Router (Orchestra):** flow sequencing, variable interpolation, control flow (`runFlow` /
  `repeat` / `retry`), artifacts and reporting. Backend-agnostic. Owns *what* command runs next,
  never *how* it touches the device.
- **Backend:** selector→element resolution, synchronization/settle, tap-retry, the driver calls.
  Owns *how*. Two implementations: `legacy` (today's behavior) and `device-core`.
- **Per-run selection.** A flow runs entirely on one backend. Per-command routing is explicitly
  rejected — the prototype already paid for its complications (process-global app-under-test,
  mid-flow slot contention, the masking above), and we are not doing it again.

Rejected alternative — a device-core-only branch with stock Maestro as the baseline. It doesn't
save the work (a naked device-core branch still has to strip Orchestra's find-loop/settle, ~80% of
the refactor) and it leaves a drifting branch plus a throwaway artifact instead of the end-state
architecture. The router makes the validation instrument and the production architecture the same
thing.

## The backend seam

The seam sits **below** device-interaction behavior and **above** flow orchestration. Roughly:

```
Orchestra (router)  ──dispatch──▶  ExecutionBackend
  flow control                       resolve(selector) → element
  variables                          synchronize / settle
  artifacts + reporting              act (tap / input / scroll / launch …)
  backend selection                  returns result + evidence
```

The interface is "execute one resolved command, return its result/evidence." `legacy` implements it
by wrapping today's `Maestro`/`Driver` calls (find-loop, `waitForAppToSettle`, tap-retry — all of
it). `device-core` implements it against `connect → screen → getBy* → tap/inspect`, with **its own**
evidence-based synchronization and **none** of legacy's quiescence settling.

This is where the find-loop moves. Today it straddles Orchestra (`Orchestra.findElement`,
`Orchestra.kt:1422`) and Maestro (`findElementWithTimeout`, `Maestro.kt:533`); the refactor pulls
Orchestra's half down so the whole loop lives in the `legacy` backend and the `device-core` backend
never inherits it.

The interface also carries a **lifecycle** — `open()` (provision + connect) and `close()`
(teardown) around `execute` — because a device has to be stood up before any command runs, and
provisioning is backend-specific. See §Device connection, provisioning & lifecycle.

## The baseline trap, and how we neutralize it

The differential test is only meaningful if the baseline is behaviorally identical to today's
shipping Maestro. Refactoring the legacy path risks moving the baseline — then any divergence we see
could be our refactor, not device-core. Two rules:

1. **Relocate, don't rewrite.** The `legacy` backend is a verbatim cut/paste of today's
   find-loop / settle / retry into a new home. No cleanup, no "while we're here." Same logic,
   same constants (`lookupTimeoutMs = 17000`, `optionalLookupTimeoutMs = 7000`, the four settle
   signals, `MaestroTimer.withTimeoutSuspend`'s hot-poll).
2. **Gate the program on a refactor-safety pass.** Before device-core enters the picture, run the
   corpus on `legacy` backend vs `stock main` and require **zero divergence** (verdict, chosen
   element, coordinates, per step). If it's not identical, the relocation is wrong — fix it then.
   This pass de-risks every number the harness produces afterward.

## Device connection, provisioning & lifecycle

The router splits *how a device is provisioned* from *how commands run*, and provisioning is
backend-specific — so this has to be contemplated before the plan, not discovered mid-refactor. The
prototype solved it once (self-provisioning `connect()`), but the sole-tenant, whole-flow, per-run
shape here is different.

**Where provisioning lives.** Provisioning and session lifecycle stay at the run-setup altitude —
today's `MaestroSessionManager`, *above* Orchestra — parameterized by backend. Orchestra-the-router
only dispatches commands to an already-provisioned backend; it never opens or closes a device. This
keeps the router thin and keeps the legacy provisioning path exactly where it is.

**The seam carries a lifecycle.** `ExecutionBackend` gains `open()` (provision + connect) and
`close()` (teardown) around per-command `execute`. Run-setup calls `open()` once at flow start, hands
the live backend to the router, and `close()`s at flow end.

**Legacy backend — delegate, don't reimplement.** Its `open()/close()` delegate to the existing
`Driver.open()/close()` via the current session path, byte-for-byte: Android installs the two Maestro
APKs + on-device gRPC server (`am instrument`), iOS launches the XCTest runner, closed via the same
JVM-shutdown-hook path. Relocate-not-rewrite extends to provisioning, and this is part of what the
zero-divergence gate protects.

**device-core backend — connect once, hold, close.** device-core self-provisions its *own* on-device
server from the jar-embedded binaries (`connect()` → `ResidenceProvisioner`: `adb install` →
`adb forward` → `am instrument -e port` on Android; `xcodebuild test-without-building` on iOS). In
the router the backend calls `connect()` **once at run start**, holds the session for the whole flow,
and `close()`s at run end. This is a lifecycle device-core has never been driven in — the prototype
connected per-assert and leaked the server — so it's also the first real exercise of device-core's
held-open session, i.e. the transport-under-sustained-load unknown surfaces right here. Reuse the
prototype's self-provisioning mechanics (device-core #84), including the `-x buildMcpViewer` CLI build
flag and the forward-port selection.

**App install ≠ driver provisioning — keep them separate.** Installing the app-under-test (the corpus
run's `app.apk`) is a run-setup step, owned by the corpus runner, done once before either backend
opens — not a backend concern. Backend `open()` provisions only the *driver server*. Don't let the
two conflate the way the prototype's per-op connect did.

**App-under-test identity into device-core.** device-core needs the flow's `appId` to bind its
connection, and the prototype passed it via process globals (`devicecore.ios.bundleId`,
`System.setProperty`) — which race and assume one run per JVM. The backend must pass `appId` in
cleanly at `open()`. Note device-core's `TargetSelector` **can't name the app today** (a known
surface gap); for sole-tenant single-run validation, setting it at the one run boundary is
acceptable, but flag it — it may be a device-core surface change the mini-spec has to close.

**Single-device topology is a feature here, not a limit.** device-core ignores adb serial and selects
by a process-global forward port (single-emulator by construction). The host pool runs one fresh
`research_spike_*` AVD per host, so that constraint is satisfied for free — no multi-device work. The
corpus runner runs flows **sequentially** per host with clean teardown between (backend `close()`
stops device-core's server; fresh device state avoids port/state carryover).

**Provisioning cost is not under test.** device-core's cold provision (adb install + `am instrument`,
or `xcodebuild`) adds run-start latency that differs from legacy's and by platform. The differential
harness compares command-level behavior, not cold-start time — don't let provisioning latency read as
a divergence.

## What we build (the known scaffolding)

Everything here is work we're confident needs to exist. None of it is a device-core capability.

### 1. Orchestra → router + `ExecutionBackend` interface
Extract the backend interface; move device-interaction behavior below it; leave flow/variable/
control/reporting above it. This is the enabling change and the riskiest — it touches the whole
command surface and its tests. Build the pattern against a small command set first (enough to run
one flow end-to-end on the `legacy` backend), confirm the shape holds, then finish the surface.
Per the decision to build fully: once the pattern is proven there's no reason to leave it partial.

### 2. `legacy` backend + refactor-safety gate
The verbatim relocation from §The baseline trap, plus the zero-divergence corpus pass that gates
the rest. Deliverable: `legacy` backend == `stock main` across the corpus, proven, before any
device-core work is trusted. **Run this on the remote host pool** via device-core's
`run-spike-experiment` skill (`~/codes/maestro-device-core/.claude/skills/run-spike-experiment/`,
`run_remote.sh` for the run-time host parse) — build off-box and copy in, fresh `research_spike_*`
AVDs only, pull results as a verified tar. All 6 hosts (`android_agents` arm-m2m /
`ios_agents` arm-m4s) are currently free; a full-corpus two-backend pass fits comfortably (see §6).

### 3. `device-core` backend
Implements `ExecutionBackend` against device-core's surface. **Mine the prototype PR (#3487, branch
`devicecore-integration-prototype`) liberally** — it already solved a pile of concrete integration
problems we'd otherwise re-solve: self-provisioning `connect()` that brings up the on-device server
from jar-embedded binaries, the mavenLocal-first-scoped-to-group `settings.gradle.kts` wiring, the
injectable `providerFactory` + `FakeDeviceProvider` test rig (seam tests with zero device I/O), the
pure `ElementEvidence → verdict` adapter, and the env-flag opt-in. Consume the published jar
(`dev.mobile.devicecore:prototype`) the same way. What we *drop* from the prototype: the per-command
routability predicate and every piece of the co-residence machinery (slot lease, the process-global
`devicecore.ios.bundleId`). At the start this backend can only execute the handful of commands
device-core has built (Android `inspect` Id+Text, `tap` Id-only; iOS `inspect` Text+Nth) — that's
fine; the command build-out is the separate track (see the parallel mini-spec). The backend's job is
to make "run this flow on device-core, naked" a real code path.

### 4. Transport hardening — op timeout + typed death
Two fixes device-core needs to survive a long flow, and both are known build tasks, not unknowns:
- **Op-level timeout on `rpc()`.** Today `LineRpc.kt:10-14` sets no connect timeout and no
  `soTimeout`, so one wedged op hangs the flow forever. The bounded-socket pattern already exists in
  device-core's own tree (`SocketPrecondition`, 500 ms probe) — apply it to the op path.
- **Typed death instead of raw `IOException`.** `tap` currently surfaces a dead server as an
  uncaught `IOException` out of `resolveLive` (`Resolver.kt:159-164`), while `inspect` degrades to
  `Resolution.Unavailable`. Make the act path symmetric — a typed failure the backend can classify,
  not a raw throw. (Reconnect/recovery is *not* in scope here — see unknowns.)

These are small, and they're the difference between "the harness reports a device-core gap" and "the
harness hangs and we learn nothing."

### 5. Observability for the device-core path
A device-core-owned failure has to be triageable. Legacy writes the whole tree per step
(`screen-hierarchy/step-N.json`, `ArtifactsGenerator.kt:240-254`) and a screenshot; device-core's
`hierarchy` and `screenshot` are both deferred (roadmap S4). Without them, a failed device-core run
is a raw `NotImplementedError` with no picture of what device-core saw — the instrument is blind to
its own failures. Build the minimum to triage: capture what device-core *did* resolve (the evidence
it returned per step) and enough screen state (a screenshot at minimum) to see the failure. This may
mean pulling a thin `screenshot` forward — acceptable, it's a prerequisite for the harness, not a
capability under test.

### 6. Differential harness over the corpus
Run a flow on both backends and diff. Diff on more than pass/fail:
- **verdict** per step (pass / fail / error),
- **chosen element + coordinates** per step,
- **first divergent step** (where the two runs part).

The coordinate/element diff is the point — device-core can go green having picked a *different*
element than legacy (bounds-visible vs occlusion-visible; spatial vs snapshot-order index), and a
naive pass/fail diff misses it. Coverage falls out for free: every command device-core declines to
execute is a logged gap, and the frequency distribution across the corpus ranks what to build next.

**Corpus sizing — how much to run.** The corpus (`~/maestro-replay-harness`) is **59 runs across 30
customer apps** (DoorDash, Amazon One Medical, crypto.com, Komoot, Kraken, Skyscanner, …), each run
carrying its own flow workspace, `app.apk`, and a baseline console log. That's small — no sampling
gymnastics needed:
- **Iterate** the refactor on a **~6-flow smoke set** hand-picked to span command types (a tap, a
  text-input, a scroll, an assert, a launch+clearState), so the inner loop is fast.
- **Gate and measure on the full 59.** For the §2 refactor-safety gate, run all 59 — a subset lets a
  relocation regression hide in an untested app, and 59 is cheap. For the device-core divergence
  run, also the full 59: 59 flows × 2 backends ≈ 118 runs across 6 hosts is a few hours, not a
  budget problem. Under-testing (a subset) hides regressions; over-testing isn't a risk at this
  scale because there is no larger corpus to grind.
Run both passes on the remote host pool (§2). The one thing to settle is the diff *tolerance*
(coordinates won't be pixel-identical across two resolution paths even when both are right) — see
open questions.

### 7. Dependency / versioning cleanup
The validation artifact today is a Mac-only, hand-published, moving `0.1.0-SNAPSHOT` jar with native
binaries baked in (ubuntu CI can't build them). For a *validation* program that's a reproducibility
hazard — you can't pin what you measured. Move to real published versions (or a SHA-stamped
classifier) so a harness run names an exact device-core build. The Gradle version gap (device-core
9.5.1 vs Maestro 8.13) rules out an `includeBuild` composite; the JVM is already reconciled
(device-core emits JVM-17 bytecode, so there's no runtime boundary — it runs as 17 inside Maestro's
JVM).

## What the flows will probe (the genuine unknowns — NOT built here)

The scaffolding above exists so these can be *discovered*, not pre-solved. Listing them so we don't
confuse building with discovering:

1. **Settle fidelity.** Does evidence-based synchronization actually hold on real flows, where
   legacy leans on four device-level signals (`is-loading`, `isWindowUpdating`, iOS screen-static,
   screenshot-diff) that a hierarchy snapshot doesn't carry? `delivered` today means
   "accepted for dispatch," not "landed"; `settle` is always null. This is the sharpest unknown.
2. **Element-selection divergence at corpus scale.** How often, and how badly, does device-core pick
   a different element than legacy — relational (`rightOf`/`below`), state-as-selector
   (`checked:false` to *pick*), occlusion visibility, spatial `index`. And whether `Within`/`Having`
   resolve atomically device-side or reintroduce the #3242 cross-snapshot staleness bug.
3. **Transport under sustained real load.** Does the one-connection-serialized server wedge; does an
   app relaunch mid-flow survive; does anything need reconnect/keepalive (deliberately not built in
   §4 — we want to learn whether the flows demand it).
4. **Which relational selectors real flows actually need** — the held-out directional operators are
   held out "until customer usage forces them"; the corpus is that forcing function.
5. **The bigger bets** (likely beyond this round): Web/WebView as a second interaction plane (a
   rewrite, not an add, per roadmap) — and Maestro's real flows lean on webviews heavily; and
   injected-strategy viability under iOS-26 dyld hardening.

## Sequencing

1. Orchestra → router + `ExecutionBackend`, proven on a small command set, then finished (§1).
2. `legacy` backend relocation + **zero-divergence gate** vs stock main on the corpus (§2). Hard
   gate — nothing downstream is trusted until this is green.
3. Differential harness + corpus runner (§6), and dependency pinning (§7), in parallel with 2.
4. `device-core` backend (§3) + transport hardening (§4) + observability (§5).
5. Run the corpus on `device-core` backend; produce the divergence + coverage report. Hand off to
   the capability build-out (separate track) ranked by that report.

## Explicitly out of scope

- **device-core capability build-out** — the specific commands and the flows that exercise them are
  a separate track; this spec builds the harness they run through.
- **Per-command routing / hybrid fallback** — rejected above.
- **Co-residence / slot lease / dual tenancy** — abandoned; device-core is sole-tenant.
- **Multi-device, sharding, parallel, `--device` fan-out** — device-core is single-device by
  construction (serial ignored, forward-port process-global); out of scope for single-emulator
  validation.
- **Reconnect / keepalive / crash recovery** — a §4 op-timeout keeps a wedge from hanging the run;
  whether real recovery is *needed* is an unknown the flows answer.
- **Production default-on** — this is an instrument, off by default, opt-in per run.

## Open questions

- **Backend interface granularity** — is "execute one resolved command" the right unit, or does the
  seam need to sit at selector-resolution vs act separately? (Leaning: one command unit; revisit if
  synchronization straddles it awkwardly.)
- **Platform first** — Android is more built (`tap` Id-only, `inspect` Id+Text) than iOS (`tap` an
  unbuilt stub); the same flow learns different things per platform. Tied to the separate command
  audit; decide together.
- **Observability depth (§5)** — how much to pull forward. Minimum to triage, but "minimum" needs a
  line: evidence-per-step only, or evidence + screenshot, or a thin hierarchy too?
- **Corpus diff tolerance** — coordinates won't be pixel-identical across two resolution paths even
  when "correct." What's the divergence threshold that counts as a real difference vs noise?
- **App-under-test identity in device-core** — grow `TargetSelector` to name the app (a device-core
  surface change, mini-spec territory) vs the harness setting it at the single run boundary. The
  latter unblocks validation now; the former is the coherent long-term shape.
