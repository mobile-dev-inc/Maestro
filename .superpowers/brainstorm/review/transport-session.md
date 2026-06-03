# Transport / Session / Parallelism — Deep Review

> Reviewing the proposal that the new core have **ONE persistent, bidirectional,
> resumable channel per device session** carrying commands + events + observation, with
> per-command deadlines, a unified log/artifact subscription, and clock-sync — replacing
> today's per-command HTTP/gRPC-poll, with a thin on-device agent doing capture +
> event-push + primitive input.
>
> Sources reviewed: `worked-example-core-contract.md`,
> `research/contract-comparison.md` (Dim. 5), `research/artifacts-and-logging.md`
> (§7–§11, clock-sync §8), `research/ios-snapshot-levers.md`. Code read directly in
> `maestro-client`, `maestro-android`, `maestro-ios-driver`, `maestro-ios-xctest-runner`,
> `maestro-cli`. PR archaeology via `git log -S` / `gh pr view`.

---

## (a) Maestro today

### Android

**Connection establishment (`MaestroSessionManager.createAndroid` →
`AndroidDriver.open`):**

1. The host picks a `Dadb` handle (`Dadb.discover()` / `Dadb.list()` / `AdbServer` on
   port 5038). `dadb` speaks the **ADB wire protocol directly** — it does NOT go through
   the `adb` binary's `forward`/host server for the main channel (see #3171 below).
2. `installMaestroApks()` pushes two APKs (`dadb.install`), then
   `startInstrumentationSession()` runs `am instrument -w -e port 7001 ...
   dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner &` over a **dedicated
   `dadb.openShell` stream** held open for the life of the session
   (`instrumentationSession: AdbShellStream`, `AndroidDriver.kt:95,132`). Killing this
   stream is how the on-device server is torn down.
3. Inside that instrumentation process, `MaestroDriverService#grpcServer`
   (`maestro-android/.../MaestroDriverService.kt:86`) starts a **Netty gRPC server**
   (`NettyServerBuilder.forPort(7001)`) bound on the device.
4. The host gRPC channel
   (`AndroidDriver.kt:79`) is an `OkHttpChannelBuilder.forAddress("localhost", 7001)`
   whose `.socketFactory(AdbSocketFactory { _, port -> dadb.open("tcp:$port") })`
   makes gRPC's OkHttp transport open a **fresh ADB stream** (`tcp:7001`) and wrap it as
   a `java.net.Socket` (`AdbSocketFactory.kt`). So one HTTP/2 (gRPC) connection runs
   *over an ADB stream multiplexed on the same underlying ADB transport*.
5. `awaitLaunch()` polls `dadb.open("tcp:$hostPort").close()` until the server answers.

So Android is **already close to "one persistent channel"** for the *RPC verbs*: a
long-lived HTTP/2 gRPC channel (`deviceInfo`, `viewHierarchy`, `screenshot`, `tap`,
`inputText`, `launchApp`, `setLocation`, `addMedia` stream, etc.). It is request/response
(unary) except `addMedia` (client-streaming). There is **no event push from device to
host** — synchronization is host-driven poll (`isWindowUpdating`, repeated
`viewHierarchy`).

**Channel config (load-bearing):**
- `keepAliveTime(2m) / keepAliveTimeout(20s) / keepAliveWithoutCalls(true)`
  (`AndroidDriver.kt:82-84`); server side
  `permitKeepAliveTime(30s) / keepAliveTimeout(20s) / maxConnectionIdle(30m)`
  (`MaestroDriverService.kt:102-105`).
- `.enableRetry().defaultServiceConfig(GRPC_RETRY_SERVICE_CONFIG)` — maxAttempts 4,
  0.5s→2s backoff, retryable only on `UNAVAILABLE` (`AndroidDriver.kt:85-86,1335`).
- Per-call deadline: `blockingStubWithTimeout = blockingStub.withDeadlineAfter(120s)`
  (`AndroidDriver.kt:89`). **Already a per-command deadline.**
- `runDeviceCall` (`AndroidDriver.kt:1273`) maps `StatusRuntimeException` codes to
  rethrows/logs; `callViewHierarchy` has a bespoke 1-retry for a UiAutomator NPE.

### iOS

**Connection establishment
(`MaestroSessionManager.createIOS` → `LocalXCTestInstaller.start`):**

1. Build/extract the driver products, then for a **simulator**
   `xcrun simctl launch ... SIMCTL_CHILD_PORT=22087` the
   `dev.mobile.maestro-driver-iosUITests.xctrunner` bundle (or `xcodebuild
   test-without-building`); for a **real device** `xcrun devicectl device process
   launch` (`LocalIOSDeviceController.launchRunner`, `LocalSimulatorUtils.launchUITestRunner`).
2. Inside the runner, one long-lived XCUITest "test" (`maestro_driver_iosUITests`) starts
   a **FlyingFox HTTP server** bound to `127.0.0.1:22087`
   (`XCTestHTTPServer.swift:32`) with one route per verb (`Route` enum:
   `viewHierarchy`, `screenshot`, `touch`, `swipeV2`, `inputText`, `launchApp`,
   `deviceInfo`, `status`, …). This is the WDA pattern (an XCUITest test that *is* an HTTP
   server) but home-grown rather than WebDriverAgent.
3. `start()` polls `GET /status` until 200 (`xcTestDriverStatusCheck`).
4. The host talks to it with a plain **OkHttp client per call**
   (`XCTestDriverClient` → `executeJsonRequest` → `okHttpClient.newCall(...).execute()`).
   Read timeout **200s**, connect timeout 1s, call timeout 200s
   (`XCTestDriverClient.kt:19-28`).

**For a simulator there is NO port forwarding** — the sim shares the host network, so
`127.0.0.1:22087` reaches the runner directly. **For a real device**, the runner binds
`127.0.0.1` *inside the device*; reaching it requires usbmux/iproxy-style forwarding.
Today's `LocalXCTestInstaller` always builds an `XCTestClient(host=127.0.0.1, port)` and
the real-device launch goes through `devicectl` — the real-device path is the least
exercised and where transport assumptions are weakest.

**Load-bearing iOS choices:**
- **Quiescence is deliberately disabled.** `XCUIApplicationProcess+FBQuiescence.m`
  swizzles `waitForQuiescence` to a no-op when `fb_shouldWaitForQuiescence` is false;
  Maestro sets it false and rolls its own **poll-and-diff** of view hierarchies
  (`ios-snapshot-levers.md:147-211`). Apple's native idle hangs forever on apps with
  perpetual animations/timers (RN), which is *exactly* the signal the proposal wants to
  push.
- **"First timeout latches the channel dead"** (`XCTestDriverClient.kt:40-77`):
  `@Volatile transportDead`; the first `SocketTimeoutException` trips the latch, and every
  later call fail-fast throws `XCUITestServerError.Unreachable` instead of issuing a fresh
  200s request. `restartXCTestRunner()` is the only thing that clears it.

### PR archaeology (and whether the rationale still holds)

| Choice | PR / commit | Why | Still holds? |
|---|---|---|---|
| **gRPC over a *direct* ADB socket** (replacing `dadb.tcpForward`) | **#3171** `d727ed04` (2026-04) | Eliminates host-side port allocation (no port conflicts) and one network hop. Benchmarks in the PR body: assertVisible flow **~41% faster** (12.7s→7.5s), fill_form **~18% faster** (27.2s→22.4s). | **Yes, strongly.** This is the single most relevant precedent for the proposal: removing a hop on the transport gave double-digit wins. Any new design must not regress this. |
| **gRPC channel-level retries** on `UNAVAILABLE` | **#3290** `3e17f7c0` (2026-05) | Prod data: **109 INFRA_ERRORs / 14d** from `StatusRuntimeException`, 85% in `callViewHierarchy`, on the **"pod-local network"**; each failure re-queued the run **on a different worker** (~30–60s). | **Yes.** And it is direct evidence the deployment is a **cloud worker pool**, and that the host↔device path crosses a real (lossy) network in prod, not just localhost. |
| **iOS HTTP-layer retry on transient 5xx/IOException** | **#3291** `a71e5b59` (2026-05) | XCTest runner returns transient 5xx (`kAXErrorInvalidUIElement`) while UI updates; retry at HTTP layer so the runner sees one logical call. | Yes — symptom of poll-and-diff against a mutating tree; an event/settle signal would reduce it. |
| **iOS "latch transport dead / fail-fast"** | **#3212** `e5da6240` (2026-04) | A `swipe` deadlocked mid-job; every subsequent call (`onCommandFailed` hierarchy snapshot, `retry` replays, `onCommandStart` screenshot) ate another **200s**, wasting **~10 min** until the 15-min cooperative `withTimeout` fired. Latch short-circuits. | **Partially.** It is a *workaround for a missing transport health/liveness signal* and for `retry` replaying transport faults as if they were test faults. A resumable channel with explicit liveness + the proposal's `category:"infra"` error model would make the latch unnecessary — so do NOT preserve the latch as-is; preserve the *requirement* it satisfies. |
| **FlyingFox HTTP runner** | `dd646570` (2023-04, renamed); device intro `8570a54a` (#610, 2023-01) | Mirrors WDA: an XCUITest test hosting an HTTP server is the only sanctioned way to drive XCUITest out-of-process. | Holds as the *capture/input mechanism*; the *HTTP-per-call transport* on top of it is the replaceable part. |
| **Parallel sessions via `SessionStore` heartbeats** | **#575** `3d6e0ec6` (2023-01, "allow multiple parallel Maestro sessions") | Let multiple `maestro` processes share one device's already-installed driver instead of fighting over install/uninstall. | Holds for the *local* case; does nothing for cloud (see (c)). |

---

## (b) The real multi-connection picture

The proposal's "one channel" framing only describes the *command/observation RPC*. A real
run uses **many** independent connections/processes per platform. Enumerated:

### Android (per session)
1. **ADB transport** (one TCP socket to adb server, or USB) — the substrate everything
   below multiplexes onto.
2. **gRPC-over-ADB-stream** (`tcp:7001`) — the command/observe/screenshot RPC channel.
   (OkHttp may open >1 ADB stream under HTTP/2; conceptually one logical channel.)
3. **Instrumentation shell stream** (`dadb.openShell("am instrument …")`) — held open the
   whole session; its lifetime *is* the on-device server's lifetime. Separate from #2.
4. **Per-action `dadb.shell` exec streams** — `input swipe`, `input keyevent`, `am
   force-stop/kill/start`, `pm clear/grant/list`, `settings put` (proxy, rotation,
   airplane, locale), `appops`, `su root`. **Each is a fresh ADB stream**, NOT over gRPC
   (`AndroidDriver.kt` ~30 callsites). longPress, pressKey, swipe, back, hideKeyboard,
   openLink, proxy, orientation, setAirplane all bypass the "one channel".
5. **`screenrecord` + `dadb.pull`** — video is captured by a shell `screenrecord` to
   `/sdcard`, then pulled over yet another ADB stream on stop (`AndroidDriver.kt:538-565`).
6. **Chrome DevTools / WebView** — a *separate* OkHttp client over
   `dadb.open("localabstract:<webview-socket>")` with a `DummyDns`
   (`DadbChromeDevToolsClient.kt:82-89`) for hybrid/RN WebView hierarchy.
7. **logcat** — note: device log capture for Android is **largely absent today** (the
   research flags this as a gap), so it isn't even a connection yet, but it must become one.
8. APK install/uninstall streams (open/close).

### iOS (per session)
1. **XCTest HTTP** (`127.0.0.1:22087`) — command/observe/**screenshot** RPC (FlyingFox).
2. **`xcrun simctl` / `devicectl` subprocesses** — a *new OS process per call* for:
   launch runner, install, permissions, keychain reset, set language/locale, openURL,
   list apps, `clearKeychain`, `addMedia`. Dozens of `ProcessBuilder` invocations
   (`LocalSimulatorUtils.kt`).
3. **Video** — `xcrun simctl io … recordVideo` via `screenrecord.sh` as a long-lived
   child process piped on stdin (`LocalSimulatorUtils.kt:647`). **Not the HTTP channel.**
4. **Device logs** — `simctl spawn … log` (separate process).
5. **Runner stdout/diagnostics** — `xcodebuild`/`devicectl` stdout redirected to
   `xctest_runner_<date>.log` (`XCRunnerCLIUtils.kt:13`).
6. **Real device** adds a usbmux/iproxy forwarding layer under #1 (and devicectl tunnel
   state, `IOSDevice.tunnelState`).

**Crux verdict on "one persistent bidirectional channel":** It is *naive as stated* for
the full surface and *already substantially true for the narrow RPC slice on Android*.
The command/observe/screenshot verbs genuinely can (and on Android mostly do) ride one
channel. But **input primitives** (Android `input swipe`/`keyevent` via shell), **app
lifecycle**, **device config**, **video**, **logs**, **WebView**, **install**, and on iOS
**everything simctl/devicectl** are structurally *out-of-band*: they are OS-level
capabilities reached through ADB-exec / `simctl` / `devicectl`, not through the
test-runner agent, and several (video, logs) are intrinsically separate long-lived
streams. A single channel cannot subsume `simctl`/`devicectl` because those are host-side
tools the on-device agent has no authority over.

The honest target is **"one channel per *capability class*"**, not one channel period:
- a **control/observe channel** (the proposal's core — realistic, and the right place for
  per-command deadlines, event push, resume);
- a **log/telemetry stream** (push, push-down filtered — the proposal's "unified log
  subscription" is sound *as a subscription*, but it is a *second* connection, not folded
  into the first);
- **artifact/bulk transfer** (screenshots inline are fine; video is a separate long
  pull/stream by nature);
- **out-of-band device control** (`simctl`/`devicectl`/adb-exec) that stays host-side.

The proposal should say this explicitly. "One channel" as a slogan will mislead
implementers into trying to tunnel `simctl` and `screenrecord` through the agent.

---

## (c) Session & parallelism today + where it should live

### Today
- **Where it lives:** entirely in the **CLI process** today
  (`maestro-cli/.../session/`). `MaestroSessionManager.newSession` creates one driver per
  device, registers a `SessionStore` heartbeat every 5s into a **file-backed
  KeyValueStore at `~/.maestro/sessions`** keyed `"${platform}_${deviceId}_${sessionId}"`
  (`SessionStore.kt`). A session is "active" if its heartbeat is <21s old; a session
  prunes after 21s.
- **Isolation / reuse model:** `hasActiveSessionForDevice` decides whether *this* process
  should `connectToExistingSession` (reuse the already-installed on-device driver) or
  `open()` it; `shouldCloseSession` (no other active sessions on that device) decides
  whether the shutdown hook tears the driver down. This is purely a **local
  multi-process coordination on one machine** so two `maestro` invocations against the
  same emulator don't fight over install/uninstall (#575).
- **Parallelism across devices** is *shards over device ids*: the CLI fans flows out to N
  devices (one driver each), distinct ports per device. There is **no cross-machine
  session registry, no device pool, no scheduler** in this repo.
- **Cloud reality (inferred from PRs #3290/#3212):** a **cloud worker** is handed a
  device id + workspace and runs the same `Orchestra`/driver stack; on infra failure the
  *backend* re-queues onto a *different worker*. The "pod-local network" between
  worker and device means the host↔device transport is a real network with loss, and
  per-worker liveness/health is what the latch (#3212) and gRPC retry (#3290) are
  papering over.

### Where it should live in the proposed architecture
- **Session = the channel's lifecycle.** The proposal's `maestro.connect({deviceId,
  platform})` → `Session` is the right home for per-session identity, capability
  negotiation, the per-command deadline, and the resume token. Make **resumability a
  session property**: a worker that loses the pod-local socket should `reconnect(resumeToken)`
  and continue, instead of the backend re-queuing onto a new worker (which #3290 shows
  costs 30–60s each and happens ~109×/14d). This is the strongest *new* justification for
  the proposal that the proposal under-sells.
- **Parallelism does NOT belong in the channel.** Every mature framework (below) isolates
  parallel runs by **process/port-per-session + device pooling above the transport**, never
  by multiplexing many sessions onto one channel. The proposal must keep
  device-pool/scheduling **outside** the core: the core owns *one device session*; a
  **separate orchestrator/registry** (today's `SessionStore`, tomorrow a cloud scheduler)
  owns *which session runs where*. The `SessionStore` file-DB is fine locally but is a
  **specific gap for cloud** — it needs to become a real distributed registry, and that is
  an orchestration concern the transport contract should *enable* (stable session id +
  resume) but not *contain*.
- **Out-of-band control ownership:** keep `simctl`/`devicectl`/adb-exec on the host worker
  (they require host tools + device authority); the channel carries only what the
  on-device agent can actually do.

---

## (d) What other frameworks do

| Framework | Transport | Session model | Parallelism | Sync |
|---|---|---|---|---|
| **Appium + XCUITest (WDA)** | **HTTP REST** (W3C WebDriver) to an XCUITest test hosting an HTTP server (RoutingHTTPServer) at `:8100`; real device via **appium-ios-device / iproxy / go-ios** USB forward. | One WDA per session; `session/<id>/…` endpoints. | **Port-per-session**: `wdaLocalPort` (iOS, default 8100), `systemPort` 8200–8299 (Android UiAutomator2), unique **derivedDataPath** per driver. Historically one Appium server per device in a Selenium grid; 1.7+ multiplexes sessions on one server but **still separate WDA ports**. | Poll; `waitForQuiescence` available but flaky (same reason Maestro disabled it). |
| **Detox** (grey-box, RN) | **WebSocket**, truly bidirectional; **both the test runner and the in-app client are clients of a separate proxy WS server** — either side can drop without killing the other. | One WS pair per app instance. | Process/port-per-worker; app rebuilt with Detox. | **Best-in-class idle sync**: in-app instrumentation tracks in-flight network, animations, timers, RN bridge; the next command blocks until idle. This is *real event-driven sync* — but it requires **modifying the app** (grey-box). Maestro is black-box and cannot assume this. |
| **Appium Flutter / flutter_driver** | **WebSocket to the Dart VM service**; commands serialized as JSON to the isolate. | One VM-service WS per app. | Per-session ports. | VM-service can report frame/idle — again only because it's *inside* the app's runtime. |
| **Espresso / UiAutomator (Android)** | **On-device instrumentation** (`am instrument`); results over the instrument stream / per-host orchestration. Espresso↔Flutter uses a **cleartext WebSocket**. | In-process to the app (Espresso) or system (UiAutomator). | Process/device sharding; gradle-managed devices. | Espresso has true `IdlingResource` sync (in-process). |
| **EarlGrey** | In-process (eDistantObject RPC). | In-process. | Sharding. | `GREYUIThreadExecutor` drains the run loop — principled idle (in-process). |
| **WebdriverIO** | HTTP (W3C) to Appium/cloud; for browsers increasingly **CDP/BiDi over WebSocket**. | Session per capability. | Worker-per-spec, instance ports. | Wait/poll; BiDi adds event subscriptions. |

**Pattern that actually generalizes:**
1. **Bidirectional/event-driven transports (Detox WS, Flutter VM-service, CDP/BiDi) all
   require being *inside* the app or runtime.** Every framework that has the proposal's
   "device pushes idle/changed" capability is **grey-box**. Black-box drivers (WDA,
   UiAutomator, Maestro) are stuck with request/response + poll precisely because they
   cannot get a reliable push without instrumenting the app — which is the same reason
   Maestro disabled XCUITest quiescence.
2. **Nobody multiplexes parallel runs onto one channel.** Universal answer:
   **port-per-session, process/derived-data isolation, a pool/grid above the transport.**
3. **Resumable/persistent single channel is real where it's in-app** (Detox's "either side
   can disconnect" is the closest analog to the proposal) and partially real for
   black-box (WDA's long-lived HTTP server, Maestro's long-lived gRPC channel).

---

## (e) Honest verdict + recommendation

**What the proposal gets right:**
- **Per-command deadlines** — Android already has this (`withDeadlineAfter(120s)`); make it
  uniform and configurable. Good.
- **A persistent control/observe channel** — Android is *already there* with gRPC; #3171
  proves shaving a hop is worth ~18–41%. Formalizing one long-lived channel with explicit
  liveness is sound and matches WDA/Detox's long-lived server.
- **Resumability** — the single most valuable and *under-argued* idea. #3290 shows
  ~109 transport faults/14d each re-queuing a whole run onto a new worker; a `resumeToken`
  that lets the *same* worker reconnect and continue directly attacks that cost and makes
  the #3212 latch obsolete. This deserves to be the headline justification, not a footnote.
- **Unified log/artifact subscription + clock-sync** — correct in shape (matches OTel /
  Perfetto / Playwright / Appium per `artifacts-and-logging.md`), *provided* it is
  understood as a **separate subscription/stream**, with **push-down filtering** so the
  simulator/logcat firehose isn't dragged host-side, and clock-sync done Perfetto-style
  (snapshot pairs + drift line). Today device-log capture barely exists, so this is
  net-new value, not a rewrite of something working.

**Where it is hand-wavey or wrong:**
1. **"ONE channel" is the wrong abstraction for the whole surface.** The multi-connection
   reality (§b) is not incidental — input primitives (Android shell `input`), app
   lifecycle, device config, video, logs, WebView, and *all* of iOS `simctl`/`devicectl`
   are out-of-band by necessity. The on-device agent cannot run `simctl` or pull
   `screenrecord`. The realistic target is **one channel per capability class**
   (control/observe, log/telemetry, bulk-artifact, out-of-band host control), with the
   proposal's channel being the *control/observe* one. As written, "one persistent
   bidirectional channel carrying commands + events + observation" will mislead.
2. **Device-pushed `device.idle`/`changed` is the biggest technical risk and is asserted,
   not designed.** The worked example's trace (`s3` "device.idle pushed") presumes a
   reliable on-device idle signal. **Maestro already tried the platform's version of this
   and disabled it** (`fb_setShouldWaitForQuiescence:NO`) because XCUITest quiescence
   **hangs forever** on RN/perpetual-animation apps. Every framework that *does* push idle
   (Detox, Flutter, Espresso, EarlGrey) is **grey-box / in-app**; Maestro is black-box.
   So a black-box "device pushes idle" needs a concrete, bounded heuristic
   (animation cool-off + accessibility-tree-stable diff + hard timeout fallback) — i.e.
   it is *still* a poll/settle under the hood, just pushed one process closer to the
   capture point. The proposal presents event-driven sync as if the push is free; it is
   the single hardest item and must ship with a hard-timeout fallback or it reintroduces
   the exact hang #3212 was cleaning up after.
3. **Parallelism is unaddressed and is a stated gap.** The proposal stops at
   `connect({deviceId})`. It must explicitly state that device pooling / scheduling /
   the cross-machine session registry live **above** the channel (today's local
   `SessionStore` → a cloud scheduler), and that the channel's contribution to
   parallelism is only a **stable session id + resume token**. Don't let "session" in the
   channel be confused with "session" in the scheduler.
4. **Bidirectional is overstated for black-box.** Real bidirectionality buys you (a) event
   push and (b) cancellation/deadline propagation. (b) is genuinely valuable (cancel an
   in-flight 200s iOS call). (a) is constrained by the idle-signal problem above. gRPC
   bidi streaming (or a WS) is a fine substrate, but the *value* is resume + cancel +
   log-push, not "the device drives the conversation."

**Realistic target / recommendation:**
- **Adopt:** one long-lived **control/observe** channel per session with (i) per-command
  deadline (have it), (ii) explicit liveness/health, (iii) **a resume token** so a worker
  reconnects instead of the backend re-queuing (kills #3290 cost, retires #3212 latch),
  (iv) server→client **cancellation**. On Android this is an *evolution* of the existing
  gRPC channel (add a bidi stream for events + resume); on iOS it is a real change to the
  FlyingFox runner (HTTP → a streaming/WS endpoint) — the higher-cost side, consistent
  with `contract-comparison.md` calling iOS sync "the big one."
- **Adopt as separate streams (not folded in):** unified **log/telemetry subscription**
  (push-down filtered) and **clock-sync**; keep **video** and **bulk artifacts** as their
  own transfers.
- **Keep host-side, out of the channel:** `simctl`/`devicectl`/adb-exec device control and
  input that the agent can't perform.
- **Decouple event-driven sync** from the channel/contract (as `contract-comparison.md`
  Dim. 8 recommends): ship the persistent+resumable channel on **today's poll-and-diff**
  first, then swap in a *bounded* idle signal with a hard-timeout fallback. Never ship a
  device-idle wait without the fallback — that is the #3212 hang.
- **Name parallelism explicitly:** core owns one device session (id + resume); a
  separate orchestrator owns pooling/scheduling/registry.

**Single biggest weakness (see summary).**
</content>
</invoke>
