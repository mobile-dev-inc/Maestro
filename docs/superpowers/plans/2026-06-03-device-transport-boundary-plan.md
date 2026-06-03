# Android Device Connection Boundary — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `dadb.Dadb` invisible outside the Android device layer, and make every transport failure surface as `maestro.DeviceUnreachableException` (which the worker classifies as a retryable INFRA_ERROR) instead of a bare `IOException` laundered into `MaestroException` → TEST_ERROR. This fixes the ~174/month `UnableToSetPermissions`/`UnableToClearState` 15-minute timeouts and removes the last raw-`Dadb` leaks across all callers.

**Architecture:** Consumers name an **endpoint** and get back a **driver/device**. The device layer owns everything below that:
- `AndroidDriver.connect(host, port, …)` — owns construction. Internally: `Dadb.create(host, port)` → `DadbConnection(dadb)` → `AndroidDriver(connection)`. `Maestro.android(driver)` stays a thin wrapper; consumers compose `Maestro.android(AndroidDriver.connect(...))`. (Construction lives on the driver — the thing being built — not on `Maestro`; `AndroidDriver` stays meaningful in isolation for tests/tools.)
- `DadbConnection` — `internal` concrete class wrapping one open `Dadb`; sole holder of raw dadb; translates `IOException → DeviceUnreachableException` on every per-connection op.
- `AndroidDriver(connection: DadbConnection)` — the only constructor; called by `connect` and by tests. No consumer ever names `Dadb` or `DadbConnection`.
- Enumeration is a **separate utility** that yields endpoints, not `Dadb`.

No backwards-compatibility constraints: we control all callers (the worker is a submodule, bumped only after it's updated). This is the optimal end state, not a sequenced compromise.

**Tech Stack:** Kotlin, JUnit 5 (Jupiter), Google Truth, MockK. Modules: `maestro-client` (device layer), `maestro-cli` (CLI/MCP consumers), `maestro-worker` (worker consumer, in the copilot submodule). Transport: `dadb`.

---

## 1. Architecture (final, optimal)

```
worker  ──►  Maestro.android(AndroidDriver.connect(port = 6520))                   // known endpoint
CLI/MCP ──►  AndroidDevices.list() → pick → Maestro.android(AndroidDriver.connect(host, port))
                     │
                     ▼   AndroidDriver.connect(...) — internal to maestro-client; no consumer sees below
              Dadb.create(host, port)        // raw dadb born here, never escapes
                     ▼
              DadbConnection(dadb)            // sole owner; every op: IOException → DeviceUnreachableException
                     ▼                        //   (open/close pass through; NO discovery methods)
              AndroidDriver(connection)       // domain ops; gRPC tunnel via connection.open
                     ·
        Maestro.android(driver)              // thin wrapper, composed at the call site (not part of connect)
```

- **`dadb.Dadb` appears in exactly two spots:** `Dadb.create(...)` inside the factory, and the `DadbConnection` that wraps it. Nowhere else in the codebase.
- **`DadbConnection` exposes per-connection operations only** — `shell`, `openShell`, `pull`, `push`, `install`, `uninstall`, plus `open`/`close` pass-through. **No `create`/`list`/`discover`** — a connection wrapper enumerating other devices is nonsense; enumeration is a separate concern that *produces* the `(host, port)` to connect to.
- **`open`/`close` carve-out:** `open(destination)` backs the gRPC/OkHttp socket factory (`connection.open("tcp:$port")`), whose failures surface via the existing gRPC `runDeviceCall`; it passes the raw stream through. `close()` is lifecycle. The only two non-translating methods, both named and documented.
- **Diagnostics are device capabilities.** Logcat, crash-log, and ANR collection are methods on `Driver`/`Maestro` (screen recording already is). The worker calls those — it does not reach into a transport.
- **Error contract:** transport death → `DeviceUnreachableException` (a `RuntimeException`, deliberately not a `MaestroException`) → Orchestra lets it propagate (commit `9575a133`) → worker maps it to INFRA_ERROR/retryable. Command failures (non-zero exit) stay test-domain.
- **Testability:** the `AndroidDriver(connection: DadbConnection)` constructor is the injection seam — tests pass `DadbConnection(mockDadb)`. The public surface is endpoint-based; DI lives underneath it.

---

## 2. The one genuinely separate workstream

In scope here: the device-layer boundary across `maestro-client`, `maestro-cli`, and `maestro-worker` — i.e., everything above.

**Not in this plan (different repo, different problem):**
- **dadb-internal liveness** (dadb repo) — the write-stall timeout (#99) and the `MessageQueue.take()` `await()` deadlock fix. Fail-fast belongs in dadb (stall-based), *not* as a wall-clock timeout in `DadbConnection`. Independent of this work.
- **Per-op observability** — once the boundary exists, a per-call log line on `DadbConnection` is a trivial add if production data warrants it. Defer.
- **iOS hardening** — its own proposal.

---

## 3. Correctness checklist (from the callsite audit)

The only behavior change is a transport call's thrown type going from `IOException` (checked) to `DeviceUnreachableException` (`RuntimeException`). Every non-SAFE site and its action:

| Site | Class | Action |
|---|---|---|
| `AndroidDriver.autoVerifyWithAppName` (~606) | **SWALLOWED** — `runCatching{}` eats DUE; auto-verify silently skipped. | **Task 3:** rethrow DUE before the success gate. |
| `AndroidAppFiles.push` (~51) | **GAP** — raw `dadb.push`. | **Task 1:** `push` is a `DadbConnection` method → translated. |
| `uninstallMaestroDriverApp` (~1193) / `uninstallMaestroServerApp` (~1211) | **TYPE-NARROWED** — `catch(IOException)` made teardown "never throws"; DUE now escapes. | **Task 3:** `catch(DeviceUnreachableException)` → log and continue. |
| `startScreenRecording` (~553); `broadcastAirplaneMode` (~851); `install` (~1232); `uninstall` (~1240) | **TYPE-NARROWED — acceptable** — DUE bypasses a retry/message-wrap and propagates as infra. | No change. |
| `setAllPermissions` (~909), `setPermissionInternal` (~926) | **SAFE** — already guarded. | Keep. |
| ~20 inline driver dadb sites + `shell()` helper + `getApkFile` | **SAFE** via `DadbConnection`. | Task 1. |
| Worker `AndroidDevice` direct dadb calls (screen-record/logcat/crash/ANR) | **leak** — bypass the driver. | **Task 4:** move onto `Driver`/`Maestro`. |
| `maestro-cli` construct-and-handoff sites | replaced by `Maestro.android(port)` / enumeration. | **Task 2.** |

---

## 4. File structure

- **Create** `maestro-client/.../drivers/DadbConnection.kt` — `internal` wrapper (per-connection ops only).
- **Delete** `maestro-client/.../drivers/TranslatingDadb.kt` — replaced.
- **Modify** `maestro-client/.../drivers/AndroidDriver.kt` — sole constructor `AndroidDriver(dadb: DadbConnection, hostPort, …)`; add a companion `AndroidDriver.connect(host, port, hostPort, …): AndroidDriver` that builds `Dadb.create → DadbConnection → AndroidDriver`; simplify `shell()`; fix `autoVerifyWithAppName` + teardown; add diagnostics capabilities (logcat/crash/ANR) to satisfy the `Driver` interface. (`Maestro.android(driver)` is unchanged.)
- **Modify** `maestro-client/.../Driver.kt` — add diagnostics capability methods (logcat/crash/ANR) so the worker can drop raw dadb.
- **Modify** `maestro-client/.../android/AndroidAppFiles.kt`, `.../chromedevtools/AndroidWebViewHierarchyClient.kt`, `DadbChromeDevToolsClient.kt` — take `DadbConnection`.
- **Create/Modify** `maestro-client/.../device/` — an `AndroidDevices.list(): List<AndroidDeviceDescriptor>` enumeration utility yielding `(id, host, port)`; refactor `DeviceService` android discovery onto it (no `Dadb` returned to callers).
- **Modify** `maestro-cli/.../session/MaestroSessionManager.kt`, `McpMaestroSessionManager.kt` — use `AndroidDevices.list()` + `Maestro.android(host, port)`; stop constructing `Dadb`.
- **Modify (copilot submodule)** `maestro-worker/.../android/AndroidDevice.kt` — construct via `Maestro.android(port = 6520, …)`; use the new `Driver` diagnostics for logcat/screen-record/crash/ANR; delete `dadbFactory` / `connectedDadb`.
- **Modify** `maestro-client/.../test/.../AndroidDriverTest.kt` — inject `AndroidDriver(DadbConnection(mockDadb))`.

---

## 5. Implementation tasks (build order)

Each task is shippable and verifiable on its own; the worker (Task 4) is bumped only after Tasks 1–3 land.

### Task 1 — `DadbConnection` + driver constructor + helpers
Create `DadbConnection` (per-connection ops, `runDadbCall` → DUE, `open`/`close` pass-through, no discovery). Change `AndroidDriver`'s sole constructor to `AndroidDriver(dadb: DadbConnection, …)`; delete `TranslatingDadb`. Simplify `shell()` (drop its catch — the connection translates). Move `AndroidAppFiles` + the two chromedevtools helpers onto `DadbConnection` (closes the `push` gap). Update `AndroidDriverTest` to inject `DadbConnection(mockDadb)`; the existing assertions stay green (behavior-preserving). **Commit.**

```kotlin
internal class DadbConnection(private val dadb: Dadb) {
    fun shell(command: String): AdbShellResponse = runDadbCall("shell: $command") { dadb.shell(command) }
    fun openShell(command: String = ""): AdbShellStream = runDadbCall("openShell: $command") { dadb.openShell(command) }
    fun pull(dst: File, remotePath: String) = runDadbCall("pull: $remotePath") { dadb.pull(dst, remotePath) }
    fun pull(sink: Sink, remotePath: String) = runDadbCall("pull: $remotePath") { dadb.pull(sink, remotePath) }
    fun push(src: File, remotePath: String) = runDadbCall("push: $remotePath") { dadb.push(src, remotePath) }
    fun install(file: File, vararg options: String) = runDadbCall("install: ${file.name}") { dadb.install(file, *options) }
    fun uninstall(packageName: String) = runDadbCall("uninstall: $packageName") { dadb.uninstall(packageName) }
    fun open(destination: String): AdbStream = dadb.open(destination)   // carve-out (gRPC socket factory)
    fun close() = dadb.close()
    private inline fun <T> runDadbCall(callName: String, block: () -> T): T =
        try { block() } catch (e: IOException) { throw DeviceUnreachableException(callName, e) }
}
```

### Task 2 — Endpoint factory + enumeration; CLI/MCP onto it
Add `AndroidDriver.connect(host: String = "localhost", port: Int, hostPort: Int, …): AndroidDriver` that builds `Dadb.create(host, port, …) → DadbConnection → AndroidDriver(connection)`. Keep `Maestro.android(driver)` as the thin wrapper; consumers compose `Maestro.android(AndroidDriver.connect(...))`. Add `AndroidDevices.list(): List<AndroidDeviceDescriptor>` (the adb-server/port-scan enumeration, yielding `(id, host, port)` — never `Dadb`); refactor `DeviceService` android paths onto it. Change `MaestroSessionManager`/`McpMaestroSessionManager` to enumerate + `Maestro.android(AndroidDriver.connect(host, port))`; remove all `Dadb.create/list/discover` from them. **Commit.**

### Task 3 — Correctness fixes (swallow + teardown)
`autoVerifyWithAppName`: replace `runCatching{}` with `catch(DeviceUnreachableException){ throw }` ahead of the best-effort catch (mirror `setAllPermissions`). The two teardown methods: add `catch(DeviceUnreachableException){ log; continue }` so teardown stays non-throwing on a dead device. Tests for each. **Commit.**

### Task 4 — Worker onto the boundary (copilot submodule)
Add diagnostics capabilities to `Driver`/`Maestro` (logcat collection, crash-log, ANR; screen recording exists). In `maestro-worker/AndroidDevice.kt`: construct via `Maestro.android(port = 6520, …)`; replace every `connectedDadb.*` diagnostic call with the new `Maestro`/`Driver` methods; delete `dadbFactory`/`connectedDadb`. Worker now holds no `Dadb`. **Commit** (separate PR in copilot; bump the maestro submodule only after Tasks 1–3 merge).

### Task 5 — Verify
`./gradlew :maestro-client:test` and `:maestro-cli:compileKotlin` green; `rg "dadb.Dadb" maestro-cli maestro-worker` returns nothing outside the device layer; `rg "TranslatingDadb"` returns nothing. Update PR descriptions.

---

## 6. Decisions

**Settled:**
- Consumers create **drivers from endpoints** (`AndroidDriver.connect(port)`), composed into `Maestro` at the call site; never `Dadb` or `DadbConnection`. Construction lives on `AndroidDriver` (usable in isolation), not baked into `Maestro`.
- `DadbConnection` — `internal` concrete wrapper, per-connection ops only, no discovery.
- Sole driver constructor takes `DadbConnection`; `AndroidDriver.connect` and tests are its only callers.
- Enumeration is a separate utility yielding endpoints, not `Dadb`.
- Diagnostics (logcat/crash/ANR) are `Driver`/`Maestro` capabilities; the worker holds no transport.
- `push`/`addMedia` classify as DUE (free, it's a `DadbConnection` method). Teardown swallows DUE with a log.

**Open for sign-off:**
- Names: `AndroidDriver.connect(...)`; `AndroidDeviceDescriptor` shape.
- Build sequencing of Tasks 1–4 (all in-scope; only the order/PR-split is a preference).

---

## 7. Note on connection sharing (carried from the audit)

A connection must **not** be shared across threads. The worker today shares one `Dadb` between the gRPC reader thread and the test-runner thread, and that sharing is the source of the `MessageQueue.take()` `await()` deadlock. Each `Maestro.android(...)` builds its own `Dadb.create(...)` → its own `DadbConnection`; do not introduce a shared/singleton connection. (The deadlock itself is fixed in dadb — §2.)
