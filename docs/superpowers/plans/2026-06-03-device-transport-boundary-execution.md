# Device Transport Boundary — Grounded Execution Plan

Companion to `2026-06-03-device-transport-boundary-plan.md`. This pins the plan to the
**actual current code** (the branch already has `TranslatingDadb` from commits
`ea59a54b`/`9640d943`) and records the scope/sequencing decisions for this session.

## Scope this session

**In scope (this repo, `maestro-client` + `maestro-cli`):** Tasks 1, 2, 3, 5.

**Out of scope:** Task 4 (worker). `maestro-worker` does not exist in this repo — there
is no `.gitmodules` and no worker module. It lives in a separate "copilot" repo and the
plan defers it ("separate PR; bump the submodule only after Tasks 1–3 merge"). The
`Driver`/`Maestro` diagnostics additions (logcat/crash/ANR) belong to Task 4 and are
**deferred** — designing that interface now, with no in-repo caller, would be speculative
(YAGNI). It will be designed against the worker's real usage when Task 4 is done.

Both `/Users/stevieclifton/codes/Maestro` and `/Users/stevieclifton/codes/maestro` are the
**same directory** (case-insensitive macOS FS), same branch `fix/android-driver-device-unreachable`.

## The central design change vs. what's on the branch now

Today: `TranslatingDadb : Dadb by delegate` — it **is** a `Dadb`, so the type still leaks.
`AndroidDriver(rawDadb: Dadb)` wraps it internally (`private val dadb: Dadb = TranslatingDadb(rawDadb)`).

Target: **`DadbConnection` is a plain `internal class` that does NOT implement `Dadb`.** It
exposes only per-connection operations. Because it isn't a `Dadb`, no consumer can name
`Dadb` through it. `AndroidDriver`'s field becomes `private val dadb: DadbConnection`, and
its sole constructor takes a `DadbConnection`. This is the structural difference that makes
the boundary real rather than advisory.

## Current-state anchors (verified, with current line numbers)

- `maestro-client/.../drivers/TranslatingDadb.kt` (1–47) — `internal class TranslatingDadb(private val delegate: Dadb) : Dadb by delegate`; translates `shell/openShell/pull/install/uninstall` via `runDadbCall` → `DeviceUnreachableException`. **DELETE.**
- `maestro-client/.../drivers/AndroidDriver.kt`:
  - Constructor **67–73**: `class AndroidDriver(rawDadb: Dadb, hostPort: Int? = null, private var emulatorName: String = "", private val reinstallDriver: Boolean = true, private val metricsProvider: Metrics = MetricsProvider.getInstance()) : Driver`.
  - **78**: `private val dadb: Dadb = TranslatingDadb(rawDadb)` → becomes `private val dadb: DadbConnection` (the ctor param itself).
  - `shell()` helper **1261–1270**: already drops its transport catch (relies on translation); keep behavior, retype works unchanged since `DadbConnection.shell` returns `AdbShellResponse`.
  - `autoVerifyWithAppName` **604–629**: wraps APK pull in `runCatching{}` → **swallows DUE**. Fix: rethrow `DeviceUnreachableException` before the success gate (mirror `setAllPermissions`).
  - `uninstallMaestroDriverApp` **1189–1206** and `uninstallMaestroServerApp` **1208–1223**: nested `catch(IOException)`. DUE is a `RuntimeException`, so it now escapes teardown. Fix: also `catch(DeviceUnreachableException){ log; continue }`.
  - `setAllPermissions` **914–933** / `setPermissionInternal` **939–959**: already guard DUE correctly — **keep, use as the pattern**.
  - `startScreenRecording` **544–571** (`dadb.shell`, `dadb.pull(out: Sink, …)`), `broadcastAirplaneMode` **847–860**, `install` **1230–1236**, `uninstall` **1238–1244**: TYPE-NARROWED-acceptable — no change.
  - ~31 internal `dadb.{shell,openShell,open,pull,install,uninstall}` call sites — all keep compiling against `DadbConnection` (same method names/return types). `dadb.open(...)` is the gRPC socket-factory carve-out (e.g. **87**, **164**).
  - **No** existing `connect(...)` companion. Add one in Task 2.
- `maestro-client/.../Driver.kt` (1–113) — has `startScreenRecording` (77); **no** logcat/crash/ANR. Leave unchanged this session.
- `maestro-client/.../android/AndroidAppFiles.kt` (1–79) — `pull`/`getApkFile`/`push` all take raw `Dadb`. `push` **51** = the GAP. Retype all three to `DadbConnection`.
- `maestro-client/.../android/chromedevtools/AndroidWebViewHierarchyClient.kt` (ctor **10**) and `DadbChromeDevToolsClient.kt` (ctor **82**, uses `open`+`shell`; `main()` **240–249** uses `Dadb.discover()`) — retype to `DadbConnection`. `main()` is a dev-only entrypoint; keep it compiling (wrap the discovered `Dadb` in a `DadbConnection`), it's inside the device layer so it doesn't violate the boundary check.
- `maestro-client/.../device/DeviceService.kt` (1–712) — Android enum via `Dadb.list()` (82, 202, 426), `Dadb.create()` (186), `AdbServer.listDadbs(...)` (426); returns `Dadb`/uses `dadb.toString()` as the id. Refactor onto `AndroidDevices.list()`.
- `maestro-cli/.../session/MaestroSessionManager.kt` — `isAndroid()` probes via `Dadb.create(...).close()` (267); `pickAndroidDevice()`/`createAndroid()` build `Dadb.create/list/discover` then `AndroidDriver(dadb, …)` (291/302/339–348). Refactor to `AndroidDevices.list()` + `Maestro.android(AndroidDriver.connect(host, port, …))`.
- `maestro-cli/.../mcp/McpMaestroSessionManager.kt` — `Dadb.list().find{ … }` (81) then `AndroidDriver(dadb, null, instanceId, true)` (83). Same refactor.
- `Maestro.kt` factory **688–693**: `fun android(driver: Driver, openDriver: Boolean = true): Maestro` — **unchanged**; consumers compose `Maestro.android(AndroidDriver.connect(...))`.
- `maestro-client/src/test/.../drivers/AndroidDriverTest.kt` (22–25): `mockk<Dadb>()` → `AndroidDriver(dadb)`. Retype to `AndroidDriver(DadbConnection(mockDadb))` (or a mocked `DadbConnection`).
- `DeviceUnreachableException(val callName: String, cause: Throwable) : RuntimeException(...)`.

## Tasks (sequential — they share `AndroidDriver.kt`)

**Task 1 — `DadbConnection` + driver constructor + helpers.** Create the plain `internal`
`DadbConnection`; retype `AndroidDriver` ctor + the three `AndroidAppFiles` methods + the two
chromedevtools clients to take it; delete `TranslatingDadb`; update `AndroidDriverTest`.
Behavior-preserving (existing assertions stay green). Commit.

**Task 2 — `AndroidDriver.connect(...)` + `AndroidDevices.list()`; CLI/MCP onto it.** Add the
endpoint factory and the enumeration utility yielding `AndroidDeviceDescriptor(id, host, port)`
(never `Dadb`); refactor `DeviceService` + both session managers; remove every
`Dadb.create/list/discover` from `maestro-cli`. Commit.

**Task 3 — Correctness fixes.** `autoVerifyWithAppName` rethrow + the two teardown swallows,
with tests. Commit.

**Task 5 — Verify.** `:maestro-client:test` + `:maestro-cli:compileKotlin` green;
`rg "dadb.Dadb" maestro-cli` empty; `rg "TranslatingDadb"` empty.

Each task: implementer (TDD) → spec review → code-quality review → next.
