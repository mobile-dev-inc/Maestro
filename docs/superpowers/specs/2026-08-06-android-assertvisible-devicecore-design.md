# Spec — Android `assertVisible` → device-core `inspect()`, proving the slot lease

> **Shape: spec doc.** What we're going to build and prove on Android, in what order, and what it
> costs. Written 2026-08-06. Status: draft, pre-approval. This is the Android sibling of the iOS
> milestone-4 integration (PR #3487); it reuses that integration's routing seam and adds the one thing
> iOS didn't need — the `UiAutomation` slot lease. The goal is a rapid prototype across two worktrees
> that proves the mechanism, not production code; nothing needs to be committed to prove it.

## Context & motivation

iOS milestone 4 landed: a real legacy flow runs, one `assertVisible` is served by device-core's
`inspect()` while legacy's runner is co-resident, and the flow's verdict matches the all-legacy run.
The routing seam that did it — `Orchestra.assertConditionCommand` diverting a standalone
`AssertConditionCommand` to device-core, an `ElementEvidence → assertVisible` adapter, and a routability
predicate — is platform-agnostic and reused here as-is.

Android is the same seam plus the one mechanism iOS never exercised: **every Android read needs the
`UiAutomation` slot, and legacy holds it, so co-residence *is* the lease.** The `android-slot-lease`
spike proved the mechanism works (release-and-reacquire across two instrumentations, both directions,
API 31/34/35, 84–220 ms/transition, graded `corroborated`). What it explicitly did *not* prove is that
legacy's *own* cached handle survives the round-trip — legacy acquires the slot eagerly and caches a
`UiDevice` with a registered listener, and was never written to lose and re-fetch it. I've decided
against a separate isolated slot-lease spike. We build the integration and let it prove the lease — the
survival question is answered by the real runner, in a real flow, not a synthetic agent.

**What the recon changed vs. the original Android analysis** (the "The Android side — what it will take"
section of `2026-08-05-legacy-devicecore-integration-design.md`). Two of its Build-1 assumptions are
stale on `main`, and one Build-2 fact is confirmed harder than it was written:

- **Resolve-by-text already exists.** The original table says "resolve by text — NOT built (only
  `Selector.Id` resolves)." Not true on `main`: device-core's Android `inspect()` resolves `Selector.Id`
  *and* `Selector.Text` today. Build 1 is effectively done.
- **The Android provider chain is a full peer of iOS.** `AndroidDeviceProvider`, `TargetId.ANDROID_EMU`,
  `AndroidScreen.getByText`, `AndroidLocator`, `UiAutomationSnapshotLocateStrategy` — all wired. And
  Android needs no `bundleId` property; it selects by forward port.
- **The startup collision is real, not hypothetical.** device-core's *own* on-device server also
  acquires `UiAutomation` eagerly at startup and holds it for process life. Two eager holders on one
  device collide before any lease runs. So making device-core's snapshot acquire *on demand* isn't a
  nicety — it's the load-bearing change that makes co-residence possible at all.

## Ground truth — what the code actually says

Recon at `main` for device-core (`45cea68`) and the current Maestro tree. File refs so the design isn't
resting on memory.

| Fact | Where | Status |
|---|---|---|
| device-core Android `inspect()` resolves `Selector.Id` **and** `Selector.Text` (`byText` scans `text`/`contentDescription`/`hintText`, honors `Match` + `ignoreCase`) | `UiAutomationSnapshotLocateStrategy.kt:66-101`, `Resolver.kt:65-70,94-106` | **REAL** on `main` |
| `inspect()` returns `ElementEvidence` with `visible`/`enabled`/`attached`/bounds all MEASURED off the node; `hittable`/`stable` UNAVAILABLE | `UiAutomationSnapshotLocateStrategy.kt:103-112`, `Api.kt:290-298` | **REAL** |
| `inspect()` does **zero** host-side slot work — one `client.snapshot()` RPC over TCP loopback; the only precondition is socket reachability | `UiAutomationSnapshotLocateStrategy.kt:85`, `precondition.kt` | ground truth |
| Android provider chain exists: `connect(TargetSelector(ANDROID_EMU)) → AndroidScreen.getByText → AndroidLocator → UiAutomationSnapshotLocateStrategy` | `AndroidDeviceProvider.kt`, `AndroidScreen.kt:44-47`, `AndroidLocator.kt:42-45`, `AndroidDevice.kt:39`, `Api.kt:122-123` | **REAL** |
| Android selects by forward port (`devicecore.android.forwardPort` → env → default 8791), **not** an appId property; adb serial deliberately ignored (single-emulator) | `AndroidDeviceProvider.kt:58-61,71-82` | ground truth |
| device-core's on-device server acquires `UiAutomation` **eagerly at `serve()` startup** and holds it for process life; never `destroy()`s | `DriverServerTest.kt:43-50`, `UiAutomationDriver.ops` | ground truth |
| device-core's server uses default accessibility flags (no `FLAG_DONT_USE_ACCESSIBILITY`), OR's `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`/`INCLUDE_NOT_IMPORTANT_VIEWS`/`REPORT_VIEW_IDS` onto `serviceInfo` | `UiAutomationDriver.kt:61-65,563-566` | ground truth — matches spike constraint |
| Legacy acquires `UiAutomation` (no flags) + caches `UiDevice` **eagerly once** at gRPC-server startup, both `private val` on the long-lived `Service` | `MaestroDriverService.kt:92-94,100-101,115-118` | ground truth |
| Legacy registers `ToastAccessibilityListener` once against the cached `uiAutomation`, guarded by `isListening`, never re-armed | `MaestroDriverService.kt:125`, `ToastAccessibilityListener.kt:52-58` | ground truth |
| Legacy has **no** re-fetch / reconnect anywhere — if the slot is torn down it keeps a stale handle with no recovery | whole-module grep | **confirmed absent** |
| Legacy host-side gRPC reached via `maestro.driver` (public, typed `Driver`); `AndroidDriver` delegates transport to `AndroidDeviceConnection.execute(...)`; channel tunnels over dadb (device port 7001, no host `adb forward`) | `Maestro.kt:47-49`, `AndroidDriver.kt`, `AndroidDeviceConnection.kt:87-142,353` | ground truth |
| iOS routing seam is platform-agnostic except `DeviceCoreAssertRouter` (6 iOS points); routability predicate + verdict adapter carry no platform refs | `Orchestra.kt:514-541`, `DeviceCoreAssertRouter.kt`, `DeviceCoreRouting.kt`, `AssertVisibleVerdict.kt` | ground truth |

**Spike constraints carried in as fixed** (from `android-slot-lease` `digest.md`, all `corroborated`;
timing `claimed`):

- Legacy is the resting holder; lease on demand.
- **Never re-acquire against a still-held slot** — one lost race wedges the loser in `CONNECTING` for
  its process life (F3, `digest.md:75-81`). Gate acquisition on the `already registered!`
  `IllegalStateException` back-off (F2, `:69-73`).
- Lease with **default** accessibility flags, not `FLAG_DONT_USE_ACCESSIBILITY` (F6).
- Budget **~250 ms per transition**, growing with API level (84 → 220 ms across 31 → 35; F5, `:94-101`).
- Reassuring: a co-tenant **can't silently blind** a healthy holder — teardown throws an identity check
  (F4, `:83-92`). So a survival failure is loud, not a quiet stale handle.

## Proposal at a glance

A bespoke legacy flow runs under legacy's orchestrator on one booted emulator. One `assertVisible` on a
literal-text element is routed to device-core, exactly as on iOS. The Android-only addition is a **slot
lease** wrapped around device-core's `inspect()`: legacy yields the `UiAutomation` slot, device-core
takes it transiently for the one snapshot, legacy takes it back and re-fetches its cached state. Every
other command runs on legacy unchanged. The proof is threefold: the routed step's verdict matches
all-legacy, **legacy's runner still works after the round-trip** (the survival proof — the whole point),
and a negative control makes device-core decide *not*-visible and fail the flow exactly where legacy
would.

The genuinely new code beyond the reused iOS seam:

1. A **platform gate** on the router (Android provider / target / port property).
2. A **two-layer lease** — legacy release/re-acquire (host-driven, over legacy's existing gRPC) plus
   device-core transient acquire-on-demand (device-side, in its snapshot op).
3. A **legacy re-fetch patch** so the cached `UiAutomation`/`UiDevice`/listener survive re-acquire.
4. A **witness + timing harness** so we can verify the slot physically moved and measure the overhead.

## The design

### 1. Routing seam — reuse, with a minimal platform gate

The routability predicate (`DeviceCoreRouting`), the evidence→verdict adapter (`AssertVisibleVerdict`),
and the Orchestra decision itself (`router != null && router.canRoute(condition)` at
`Orchestra.kt:524-530`) are platform-agnostic and reused untouched. Only `DeviceCoreAssertRouter` is
iOS-coupled, at six points, each with a clean Android map:

| iOS | Android |
|---|---|
| `IosDeviceProvider()` default | `AndroidDeviceProvider()` |
| `TargetId.IOS_SIM` | `TargetId.ANDROID_EMU` |
| `if (platform != Platform.IOS) return null` | branch to the Android provider when `Platform.ANDROID` |
| `System.setProperty("devicecore.ios.bundleId", appId)` | `System.setProperty("devicecore.android.forwardPort", port)` — no appId needed |
| `connect(TargetSelector(IOS_SIM))` | `connect(TargetSelector(ANDROID_EMU))` |
| env gate `MAESTRO_DEVICECORE_ASSERT=1` | reused unchanged |

Keep **one** `DeviceCoreAssertRouter`, parameterized by provider + target + the process-global selection
property, and platform-branch inside `fromEnvOrNull`. The CLI wiring (`MaestroCommandRunner.kt:108`,
`TestSuiteInteractor.kt:191`) already calls `fromEnvOrNull(maestro, appId)` with no platform assumption,
so no change there.

### 2. The lease — two layers, host-serialized

`inspect()` is slot-free on the host, so the lease can't live inside it; it wraps around it. Two layers:

**Legacy layer (host-driven, over legacy's existing gRPC).** Add `releaseSlot()` / `reacquireSlot()` to
the `Driver` interface as **default no-ops** — the established idiom in `Driver.kt` (see
`setAndroidChromeDevToolsEnabled(...) = Unit`) — overridden only in `AndroidDriver`. iOS's driver
inherits the no-ops, so the router body is identical on both platforms and I need no separate
`SlotLease` type or downcast. `AndroidDriver` implements them via the existing
`connection.execute("releaseSlot") { it.releaseSlot(...) }` path, reusing legacy's channel; the two RPCs
go on the existing `MaestroDriver` proto service (`maestro_android.proto`), rebuilt host + device.

**device-core layer (device-side, self-contained).** Change device-core's on-device server from
eager-hold to **acquire-on-demand**: the `snapshot` op acquires the slot, applies the accessibility
flags, does `getWindows()`, and releases — so device-core holds the slot only for the one snapshot and
never at rest. Its acquire is gated on the `already registered!` back-off: if legacy hasn't fully
released, acquire throws and surfaces as `DeviceCoreUnavailable` (infra, never a verdict) — never a
retry into a wedge.

**The router body** (shared iOS/Android; the `finally` is load-bearing so legacy is never left without
its slot):

```
maestro.driver.releaseSlot()                       // no-op on iOS; destroy() on Android, returns when down
try {
    val evidence = inspect()                        // device-core acquires transiently, snapshots, releases
    AssertVisibleVerdict.pass(evidence, screenW, screenH)
} finally {
    maestro.driver.reacquireSlot()                  // no-op on iOS; re-fetch patch on Android
}
```

**The sequence, strictly serialized by the host** (this ordering is what enforces "never acquire against
a held slot" structurally, not by hope):

1. `legacy.releaseSlot()` — `UiAutomation.destroy()`; returns only when the connection is down.
2. device-core `snapshot`/`inspect()` — acquires on demand, reads, releases.
3. `legacy.reacquireSlot()` — re-fetch patch (below); returns when legacy is whole again.

The dangerous moment is step 3: legacy must never try to re-acquire while device-core still holds. Since
device-core releases inside step 2 before `inspect()` returns, and the host awaits each step, that race
can't happen on the happy path. On failure it degrades to `DeviceCoreUnavailable`, and step 3 still runs
(the `finally`) so legacy recovers its slot.

**Transient, not continuous.** device-core holds the slot only for the one snapshot; legacy is the
resting holder. This settles the spec's "continuous vs transient session" open question toward transient
for `assertVisible` — a per-command observe needs both live only at the serving instant, which is what
transient gives.

### 3. The legacy re-fetch patch (the survival mechanism)

Yielding the slot means `UiAutomation.destroy()`, which tears the connection down. Legacy caches
`uiAutomation` and `uiDevice` as `private val` and registered the toast listener on that instance, with
no code to reassign or re-register. So `reacquireSlot()` must rebuild legacy's cached state:
re-fetch `UiAutomation`, re-fetch `UiDevice`, re-apply any service-info, and re-register
`ToastAccessibilityListener` on the fresh handle. Because the fields are `val`, this needs a small
structural change to `MaestroDriverService`/`Service` so the handles are reassignable (or held behind a
re-fetchable accessor). **The re-fetch patch is in scope from the start**, not a fallback — release =
destroy makes it near-certain — but *which* of the three cached things actually needs rebuilding is what
the characterization run measures (next section), so we don't over-patch blind.

### 4. Witness + timing harness

- **Timing** — on-device stamps around each transition (`elapsedRealtimeNanos` from `destroy()`
  returning → handle `CONNECTED`), the spike's method. Emitted per routed `assertVisible`. This is the
  latency number.
- **Provenance witness** — out-of-band `adb shell dumpsys accessibility` from the host, sampling the
  slot owner before / during device-core's hold / after, confirming from `system_server`'s vantage that
  the single slot moved legacy → device-core → legacy. This is the free uplift the spike named that
  takes the proof from self-reported to independently witnessed.
- **Constraint (so the witness doesn't taint the number):** take the *timed* A→B→A cycle clean, and take
  the `dumpsys` samples on a *separate* witnessed cycle. Never measure hold-wall-clock (which would
  include the host's shell-out) and call it the transition cost. The two instruments stay independent.

## What's in, and what's out

**In:** the router platform gate; the two `Driver` RPCs + proto messages + `AndroidDriver`
implementation; device-core's server change to acquire-on-demand with the `already registered!` back-off;
the legacy re-fetch patch; the timing + `dumpsys` witness harness; one bespoke flow that exercises all
of it; the two worktrees and a Gradle composite build (`includeBuild`) so device-core edits flow into
the Maestro build without publishing.

**Out:** the general per-command router (still hardcoded "this step → device-core"); more than one
routed command; any migrated *act* (device-core's Android `tap` and injection are out); the parity
oracle / corpus replay; multi-emulator (device-core ignores the adb serial today); a faithful `visible`
pillar (we use the resolved + on-screen-bounds proxy, same as iOS milestone 4). Physical devices are
never in scope.

## Success criteria

A bespoke Maestro flow run through the CLI (`maestro test flow.yaml`) against one booted emulator, with
legacy's runner and device-core's runner both live, where:

1. **Positive path.** Launch (legacy) → a couple of ordinary legacy steps → one `assertVisible` on a
   literal-text element, routed to device-core (the lease round-trip fires here) → the flow **passes**,
   the same verdict as the all-legacy run.
2. **Survival step — the centerpiece.** *Immediately after* the routed step, a legacy command that
   exercises the cached handle — a legacy `assertVisible` (drives `ViewHierarchy.dump(uiAutomation)`, the
   direct cached-`uiAutomation` path) and/or a `takeScreenshot` — **passes**. That's the proof legacy's
   runner survived losing and re-acquiring the slot.
3. **Negative control.** Point the routed `assertVisible` at an absent element → device-core returns
   not-visible → the flow **fails at that step**, exactly where legacy would. This is what turns "green"
   into "device-core actually decided."
4. **Provenance.** device-core's log shows *its* process resolved the element, and the `dumpsys`
   witness shows the slot physically changed hands legacy → device-core → legacy.

## The survival characterization (the milestone-1 experiment)

Before patching blind, run the survival step **unpatched** to see exactly what breaks. Make
`reacquireSlot()` a no-op first (legacy keeps its stale cached handle after device-core's round-trip),
then fire post-lease legacy commands that each hit a different cached path:

- a legacy `assertVisible` → `ViewHierarchy.dump(uiAutomation)` — the **direct cached-`uiAutomation`** path
- a `takeScreenshot` → `uiAutomation.takeScreenshot()` — another direct cached-`uiAutomation` path
- a toast assertion, if cheap → the **listener** path
- (implicitly) any `UiDevice`-routed read — `UiDevice` may internally re-call `getUiAutomation()` per op
  and recover on its own; the unpatched run tells us whether it does

Whatever breaks defines the patch. My prior: the direct cached-`uiAutomation` uses break and need the
re-fetch; `UiDevice`-routed reads *might* self-heal; the listener needs re-registration. The run
replaces the prior with a measurement. Then `reacquireSlot()` rebuilds precisely what broke.

## Latency — method and a-priori estimate

The added cost per routed `assertVisible` is ~two full slot transitions (legacy release + device-core
acquire on the way in, device-core release + legacy re-acquire on the way out) plus the snapshot and the
re-fetch/re-register work. Spike transition cost was 84 ms (API 31) → 220 ms (API 35), so a rough
a-priori is **~0.2–0.6 s per routed `assertVisible`, sub-second, API-dependent** — and only paid on
legacy→core switches, which are rare early. That figure is `claimed` grade until the acceptance run's
timing harness measures it on the target emulator; producing the real number is part of the acceptance
run, after we've shown it works at all.

## Alternatives considered

### The lease seam: `Driver` default-no-ops vs. a separate injected `SlotLease`

An injected `SlotLease` collaborator (no-op binding on iOS, real on Android) would also keep `evaluate()`
shared. But the codebase already has the default-no-op interface-method idiom, and legacy's slot RPCs
have to land on the `Driver`/`AndroidDriver` anyway to reuse the existing gRPC channel — so a separate
`SlotLease` type is a second abstraction over the same mechanism. **Rejected** in favor of two
default-no-op `Driver` methods: fewer moving parts, matches house idiom, no downcast.

### Who drives the handshake: host-orchestrated vs. on-device broker vs. server-to-server

- **On-device broker** (a standalone arbiter both instrumentations talk to) is more encapsulated but is
  a whole new device-side component, and it hides the one thing we most want to watch during the
  survival proof. **Deferred** — reconsider only if host-orchestration proves too chatty.
- **device-core's server talks to legacy directly** couples the two runners, worst for a one-way
  migration where legacy is meant to be deletable. **Rejected.**
- **Host-orchestrated** reuses the two channels the host already has, serializes the dangerous step, and
  puts the survival experiment where it can be instrumented. **Chosen.**

### Patch legacy upfront vs. characterize first

Patching `reacquireSlot()` upfront and asserting "legacy works after" is faster to green but assumes
which cached state breaks and skips the measurement. **Rejected** in favor of characterize-then-patch —
the survival proof is the point, and the unpatched run is what tells us whether `UiDevice` self-heals or
the listener silently stops firing, which is the difference between a two-line patch and a wrong
assumption.

### Android shape A (host-side shim) instead of the lease

Deferred in the parent spec and still deferred: its premise (legacy runs without acquiring) is
unmeasured, it front-loads reproducing legacy's hardest artifact, and root-order cracks its fidelity.
Reconsider only if the lease's per-transition latency becomes the bottleneck at scale — nothing measured
says it will.

## Open questions

- **Which cached state survives the round-trip.** The one unmeasured fact the Android path leans on.
  Answered by the characterization run, not assumed. If more breaks than expected, the re-fetch patch
  grows — still far smaller than the shim.
- **Does `reacquireSlot()`'s re-fetch need the same accessibility service-info legacy set at startup?**
  Legacy passes no flags at `getUiAutomation` and mutates `serviceInfo` only to bust the cache. The
  re-fetch must reproduce whatever legacy's normal reads depend on; the characterization run surfaces it.
- **Settle across a window recreation** (inherited from the parent spec). The lease is measured on a
  static screen; a screen that recreates mid-lease is inferred, not measured. Only matters if a routed
  step lands during a transition — out of scope for the bespoke flow, named here so it isn't a surprise.
- **The 120 s stub deadline.** Legacy's default blocking stub deadlines at 120 s; fine for a ~250 ms
  handshake, but if `reacquireSlot()` ever blocks on contention it needs a custom stub. Noted, not a
  blocker.

## Out of scope

- The production per-command router and its config surface.
- The corpus replay / parity oracle.
- Any migrated *act* (device-core's Android `tap`/injection).
- Multi-emulator selection (device-core ignores adb serial today).
- Committing the prototype — this is a throwaway spike across two worktrees; nothing needs to land.
