# Recommendation: classify dadb transport failures by channel, not by exception type

**Date:** 2026-06-03
**Context:** `fix/android-driver-device-unreachable` — after broadening `AndroidDriver` to translate dadb
transport failures into `DeviceUnreachableException` (commits translating `shell()`, `setAllPermissions`,
and `2343fdb4` widening to all transport deaths), we have hand-picked exception-type lists in places
(`catch (SocketException)` + `catch (SocketTimeoutException)`). This doc captures why that is a smell and
what the principled fix is.

---

## TL;DR

- **Don't classify by exception subtype.** Hand-picking `SocketException`, `SocketTimeoutException`, … is
  enumerating a taxonomy you don't own (JDK sockets + okio + dadb); the set is incomplete by construction
  and drifts when dependencies bump.
- **Classify by which channel the failure came through.** At the dadb boundary the signal is not the
  exception type — it is *thrown vs. returned-with-exit-code*.
- **The correct width is `IOException`**, and you know that from the channel rule, not from a guess.
- **The list is a symptom of catching at the wrong layer.** Push translation down to the raw dadb call
  (an iOS-style `runDadbCall { }` chokepoint); then the type lists disappear entirely.

---

## The actual signal at the dadb boundary

`dadb.shell(...)` has exactly two outcomes, and they mean different things:

| Outcome | Meaning | Domain |
|---|---|---|
| **Returns** an `AdbShellResponse` with an exit code | The device serviced the request. `exitCode != 0` = the **command** failed (bad package, permission not grantable, …). | **Test** domain |
| **Throws** | The transport/protocol itself broke (broken pipe, reset, read/write timeout, EOF, okio async-timeout, protocol parse error). | **Infra** domain |

The load-bearing fact: **dadb never throws to signal "your command failed."** Command failures always
come back as a non-zero exit code. dadb only throws when the channel is dead — and every one of those
death modes is an `IOException` (`SocketException`, `SocketTimeoutException`, `EOFException`,
`ClosedChannelException`, okio's `InterruptedIOException`, `ProtocolException`, …).

Therefore the correct rule is **not** an enumerated set of subtypes. It is:

> **Any throw out of a raw dadb call = the transport is unreachable.**

This is exactly why the `shell()` change in `2343fdb4` — collapsing the two catches into a single
`catch (IOException) -> DeviceUnreachableException`, with the `exitCode != 0` check left *outside* the
`try` — is **more** correct than the hand-picked list, not less. It draws the boundary at the **channel**
(thrown vs returned), not at the **type**.

---

## How we know the correct width: `IOException` (with reasoning, not a guess)

Three candidate widths, and why `IOException` is the principled stopping point:

- **Too narrow — `SocketException`, `SocketTimeoutException`:**
  You are enumerating subtypes of a taxonomy you don't control (JDK sockets + okio + dadb). You will miss
  `EOFException`, `ClosedChannelException`, okio's `InterruptedIOException`, `ProtocolException`, etc., and
  the set silently drifts whenever dadb or okio is bumped. Ad-hoc by construction.
  *(Note: `SocketTimeoutException` is a sibling of `SocketException`, not a subclass — it extends
  `InterruptedIOException`. So even the current two-item list is only complete by luck of having spotted
  both branches.)*

- **Right — `IOException`:**
  This is the *entire checked-I/O contract* of a transport call. It is **total** over every transport-death
  mode without naming any of them. You know it is complete because `IOException` is, by definition, the type
  the language uses to mean "an I/O operation failed" — and a transport call is an I/O operation.

- **Too wide — `Exception` / `Throwable`:**
  Now you also swallow `RuntimeException` (NPE, `IllegalStateException`) bubbling out of dadb. Those are
  **bugs**, not unreachable devices; you want them to crash loudly and surface, not get silently retried as
  infra. `IOException` is the line that lets logic bugs through while catching all transport failures.

**The meta-principle:** you don't *list* types — you pick the layer where a throw is unambiguous, and catch
the whole I/O contract (`IOException`) there. The temptation to enumerate socket subtypes is the tell that
the translation is happening at the wrong layer.

---

## Why `setAllPermissions` is different — and why widening it to `IOException` would be wrong

You **cannot** simply replace the `Socket*` list in `setAllPermissions` with `catch (IOException)`, and that
asymmetry is the entire point. Its `try` block currently wraps **two different failure domains at once**:

```kotlin
val permissions = try {
    val apkFile = AndroidAppFiles.getApkFile(dadb, appId)   // raw dadb.shell + dadb.pull  -> TRANSPORT (infra)
    val parsed = ApkFile(apkFile).apkMeta.usesPermissions   // APK parse                   -> BEST-EFFORT (test)
    apkFile.delete()
    parsed
} catch (e: SocketException) { throw DeviceUnreachableException(...) }
  catch (e: SocketTimeoutException) { throw DeviceUnreachableException(...) }
  catch (e: Exception) { logger.debug(...); null }          // best-effort skip
```

- `getApkFile` → raw `dadb.shell`/`dadb.pull` → **transport** failure → must surface as infra.
- `ApkFile(apkFile).apkMeta` → **APK parse** failure (the app's APK is missing/malformed) → best-effort skip.

If you widened the catch to `IOException`, you would reclassify a genuine parse / "no APK" failure as a
retryable infra error. So the `Socket*` list is a **workaround** for the fact that the layer below
(`getApkFile`) collapses "transport threw" and the surrounding parse logic into bare exceptions at the call
site above it. The list exists only to re-separate two domains that should never have been merged.

`getApkFile` today (confirmed):

```kotlin
fun getApkFile(dadb: Dadb, appId: String): File {
    val apkPath = dadb.shell("pm list packages -f --user 0 | grep $appId | head -1")  // raw transport
        .output.substringAfterLast("package:").substringBefore("=$appId")
    apkPath.substringBefore("=$appId")
    val dst = File.createTempFile("tmp", ".apk")
    dadb.pull(dst, apkPath)                                                            // raw transport
    return dst
}
```

Its only throws are the two raw dadb transport calls. The APK parse happens *above* it, in
`setAllPermissions`. So the two domains are cleanly separable **if** translation happens at the raw calls.

---

## The principled fix: translate at the raw call, not in the business logic

This is the move iOS already made. `IOSDriver` wraps every device call in a single chokepoint and catches
**one** semantic type:

```kotlin
private fun <T> runDeviceCall(callName: String, call: () -> T): T =
    try { call() }
    catch (unreachable: IOSDeviceErrors.Unreachable) { throw DeviceUnreachableException(unreachable.callName, unreachable) }
    // ... other device-layer translations
```

iOS does **not** enumerate exception subtypes — it catches the single `Unreachable` type its device layer
raises. Android's device layer is dadb, which doesn't give us one `Unreachable` type; it gives us the
"thrown = transport dead / returned exitCode = command result" contract. So the Android equivalent catches
the I/O contract at the raw call:

```kotlin
private fun <T> runDadbCall(callName: String, block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        // A raw dadb transport call threw: the channel is dead (broken pipe, reset, timeout, EOF,
        // protocol error). This is infra, not a test failure. Command-level failures return a
        // non-zero exitCode instead and never reach here.
        throw DeviceUnreachableException(callName, e)
    }
```

Route every raw `dadb.X()` in the driver (and in `AndroidAppFiles`) through it. Then:

- **`shell()`** stops being a special case — it becomes
  `runDadbCall("shell: $command") { dadb.shell(command) }`, with the `exitCode != 0` check left *outside*
  (so a failed command stays a plain `IOException` → test domain).
- **`setAllPermissions`** **loses the `Socket*` list entirely.** A transport death inside `getApkFile` is
  already a `DeviceUnreachableException` and propagates untouched; the `try` now only needs the best-effort
  `catch (Exception)` for the `ApkFile` parse:

  ```kotlin
  val permissions = try {
      val apkFile = AndroidAppFiles.getApkFile(dadb, appId)   // throws DeviceUnreachableException on transport death
      val parsed = ApkFile(apkFile).apkMeta.usesPermissions   // parse failure -> caught below, best-effort skip
      apkFile.delete()
      parsed
  } catch (e: Exception) {
      // DeviceUnreachableException is NOT an Exception we want to swallow here — let it propagate.
      // (If runDadbCall is the source, DUE is a RuntimeException; rethrow it explicitly to be safe.)
      logger.debug("Failed to read APK permissions for $appId: ${e.message}")
      null
  }
  ```

  (Keep an explicit `catch (e: DeviceUnreachableException) { throw e }` ahead of the generic catch if any
  enclosing block could otherwise swallow it — same pattern as `setPermissionInternal` and the Orchestra
  fix.)

This collapses the three scattered translation sites — the original PR's `shell()` + `setAllPermissions`,
plus `2343fdb4`'s widening and the gRPC `runDeviceCall` arm — into **one chokepoint per transport layer**,
gives Android↔iOS parity, and removes every hand-picked exception type from the codebase.

---

## The general principle (the takeaway)

> A `catch` that **enumerates library exception subtypes** means the translation belongs **one layer down**,
> at the call where the **channel** — not the **type** — tells you what happened.

Concretely:

1. **Classify by origin/channel, not by type taxonomy.** Library exception hierarchies are implementation
   details you don't own and that drift; "did the call complete and return, or did it throw?" is stable.
2. **Put the classification at the narrowest layer where the channel is unambiguous**, and emit a single
   domain type (`DeviceUnreachableException`) from there.
3. **Catch the whole I/O contract (`IOException`) at that layer** — total over transport failures, narrow
   enough to let `RuntimeException` logic bugs surface.
4. **Never merge two failure domains into one bare exception type upstream**, or you'll be forced to
   re-split them downstream with exactly the ad-hoc type list we're trying to avoid.

---

## Proposed follow-up (separate commit on this branch)

1. Introduce `private fun <T> runDadbCall(callName: String, block: () -> T): T` in `AndroidDriver`
   (catch `IOException` → `DeviceUnreachableException`).
2. Route the raw dadb calls through it:
   - `shell()` → wrap `dadb.shell(command)`; keep the exit-code check outside the wrapper.
   - `AndroidAppFiles.getApkFile` → wrap `dadb.shell` and `dadb.pull` (or wrap the `getApkFile` call site).
   - Optionally the other direct dadb calls (`addMedia` push, `install`, `openShell`) for full parity.
3. Drop the `catch (SocketException)` / `catch (SocketTimeoutException)` list from `setAllPermissions`;
   leave only the best-effort `catch (Exception)` for APK parse, with an explicit DUE rethrow guard.
4. Reconcile with the gRPC `runDeviceCall` UNAVAILABLE arm so there is one consistent translation story.
5. **No behavior change expected** — the existing `AndroidDriverTest` (broken pipe, bare IOException,
   timeout, non-zero-exit-stays-IOException boundary guard) plus the Orchestra `IntegrationTest`
   regression tests should all stay green and prove the refactor is behavior-preserving.
