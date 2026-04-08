# SuspendingMaestro — Design Spec

## Context

PR 1 (`better-timeouts`) fixed cooperative cancellation within Orchestra — loops check for cancellation, callbacks don't fire after cancel, `CancellationException` propagates immediately. But Orchestra still calls blocking `Maestro` methods directly on the coroutine's thread. If a device IO call hangs (frozen device, unresponsive socket), the coroutine can't be cancelled until the blocking call returns.

## Problem

When `maestro.tap()` or `maestro.viewHierarchy()` blocks indefinitely on device IO, `withTimeout` and coroutine cancellation have no effect — the thread is trapped in a JVM blocking call with no suspension point. The caller's timeout fires but Orchestra never returns.

## Solution

Create `SuspendingMaestro`, a wrapper that delegates every method to the underlying `Maestro` instance via `withContext(Dispatchers.IO)`. This moves blocking device calls off the coroutine's thread. When the coroutine is cancelled, `withContext` stops waiting for the result and returns immediately — Orchestra gets `CancellationException` even though the blocking call is still running on the IO thread.

The orphaned IO thread is freed when the caller closes the `Maestro` instance (which closes the underlying socket/connection), causing the blocked IO call to throw `IOException`.

## Components

### SuspendingMaestro

**File:** `maestro-client/src/main/java/maestro/SuspendingMaestro.kt`

A wrapper class that holds a `Maestro` instance. Every public method becomes a `suspend fun` that dispatches to `Dispatchers.IO`:

```kotlin
class SuspendingMaestro(val delegate: Maestro) : AutoCloseable {

    suspend fun tap(element: UiElement, ...) = withContext(Dispatchers.IO) {
        delegate.tap(element, ...)
    }

    suspend fun viewHierarchy(...) = withContext(Dispatchers.IO) {
        delegate.viewHierarchy(...)
    }

    // All ~42 public methods wrapped identically

    // Properties delegate directly (cached/in-memory, no IO dispatch needed)
    val cachedDeviceInfo get() = delegate.cachedDeviceInfo
    val deviceName get() = delegate.deviceName

    override fun close() = delegate.close()
}
```

All methods are wrapped uniformly — no selective wrapping. The overhead of `withContext` on non-IO methods is negligible, and a consistent API avoids auditing which methods do IO internally.

### Orchestra changes

Orchestra's constructor parameter changes from `Maestro` to `SuspendingMaestro`. Command handlers that call `maestro.*` methods and aren't already `suspend fun` get the `suspend` modifier added (the compiler enforces this). No logic changes — just type and signature updates.

### Caller changes

Callers that create an `Orchestra` now wrap their `Maestro` instance:

```kotlin
// Before
val orchestra = Orchestra(maestro, ...)

// After
val orchestra = Orchestra(SuspendingMaestro(maestro), ...)
```

Callers use `withTimeout` as before for timeouts. With `SuspendingMaestro`, the timeout now works even when device IO is blocked.

## What's NOT changing

- `Maestro.kt` — stays fully synchronous, untouched
- `Driver` interface — untouched
- `AndroidDriver`, `IOSDriver`, `WebDriver` — untouched
- `MaestroTimer` — stays as-is (used within Maestro internally, already wrapped by `withContext`)

## Tests

### Test 1: Cancellation returns promptly despite blocked device IO

Validates that when a `Maestro` method blocks indefinitely, coroutine cancellation via `withTimeout` still returns promptly.

**Setup:**
- Custom `FakeDriver` (or modified existing) where `contentDescriptor()` (called by `viewHierarchy`) blocks on a `CompletableFuture` that never completes — simulating a frozen device
- Wrap in `SuspendingMaestro`, pass to Orchestra
- `withTimeout(2000)` on `runFlow` with a simple command that triggers `viewHierarchy`

**Assertions:**
- `withTimeout` throws `CancellationException` within ~2 seconds (not stuck)
- After closing Maestro in the finally block, the blocked thread is freed (the `CompletableFuture` can be completed in the driver's `close()` to unblock it)

### Test 2: Normal operation is unaffected

Validates that `SuspendingMaestro` doesn't change behavior for non-blocked calls.

**Setup:**
- Standard `FakeDriver` with normal elements
- Wrap in `SuspendingMaestro`, pass to Orchestra
- Run a flow with tap + assert commands

**Assertions:**
- Flow completes successfully
- All driver events recorded correctly
- Behavior identical to using `Maestro` directly

## Out of scope

- `FlowTimeoutException` / Orchestra-owned timeout parameter — callers use `withTimeout` directly
- Watchdog removal — can be done in a follow-up once `SuspendingMaestro` is validated in production
- `MaestroTimer` rework — separate concern
