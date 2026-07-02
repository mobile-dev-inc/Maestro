package maestro.utils

import com.google.common.truth.Truth.assertThat
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [GrpcRetry.withRetry] — bounded retry-with-reconnect for gRPC calls.
 *
 * The helper is what AndroidDriver.runDeviceCall delegates to. It exists so the
 * retry policy (max attempts, total budget, broken-pipe → rebuild channel) can
 * be tested without standing up a real gRPC channel or a device.
 */
internal class GrpcRetryTest {

    @Test
    internal fun `success on first attempt returns immediately without retry or reconnect`() {
        val attempts = AtomicInteger(0)
        val reconnects = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()

        val result = GrpcRetry.withRetry(
            callName = "deviceInfo",
            sleepMs = { sleeps.add(it) },
            onBrokenPipe = { reconnects.incrementAndGet() },
        ) {
            attempts.incrementAndGet()
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(reconnects.get()).isEqualTo(0)
        assertThat(sleeps).isEmpty()
    }

    @Test
    internal fun `UNAVAILABLE then success — retries and returns the recovered value`() {
        val attempts = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()

        val result = GrpcRetry.withRetry(
            callName = "deviceInfo",
            sleepMs = { sleeps.add(it) },
        ) {
            val attempt = attempts.incrementAndGet()
            if (attempt < 3) throw Status.UNAVAILABLE.asRuntimeException()
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(3)
        // Two retries → two sleeps between attempts 1→2 and 2→3.
        assertThat(sleeps).hasSize(2)
    }

    @Test
    internal fun `UNAVAILABLE on every attempt — bubbles the last error after maxAttempts`() {
        val attempts = AtomicInteger(0)

        val ex = assertThrows<StatusRuntimeException> {
            GrpcRetry.withRetry(
                callName = "deviceInfo",
                maxAttempts = 3,
                sleepMs = { /* no-op */ },
            ) {
                attempts.incrementAndGet()
                throw Status.UNAVAILABLE.withDescription("attempt failure").asRuntimeException()
            }
        }

        assertThat(ex.status.code).isEqualTo(Status.Code.UNAVAILABLE)
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    internal fun `INVALID_ARGUMENT is not retried — bubbles immediately`() {
        val attempts = AtomicInteger(0)

        val ex = assertThrows<StatusRuntimeException> {
            GrpcRetry.withRetry(callName = "tap", sleepMs = { /* no-op */ }) {
                attempts.incrementAndGet()
                throw Status.INVALID_ARGUMENT.asRuntimeException()
            }
        }

        assertThat(ex.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    internal fun `DEADLINE_EXCEEDED is not retried — emulator is hung, retry would wait again`() {
        val attempts = AtomicInteger(0)

        val ex = assertThrows<StatusRuntimeException> {
            GrpcRetry.withRetry(callName = "viewHierarchy", sleepMs = { /* no-op */ }) {
                attempts.incrementAndGet()
                throw Status.DEADLINE_EXCEEDED.asRuntimeException()
            }
        }

        assertThat(ex.status.code).isEqualTo(Status.Code.DEADLINE_EXCEEDED)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    internal fun `INTERNAL is not retried — surfaces server-side error to caller`() {
        val attempts = AtomicInteger(0)

        val ex = assertThrows<StatusRuntimeException> {
            GrpcRetry.withRetry(callName = "tap", sleepMs = { /* no-op */ }) {
                attempts.incrementAndGet()
                throw Status.INTERNAL.asRuntimeException()
            }
        }

        assertThat(ex.status.code).isEqualTo(Status.Code.INTERNAL)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    internal fun `UNAVAILABLE with IOException cause — invokes onBrokenPipe before retrying`() {
        val attempts = AtomicInteger(0)
        val reconnectsBeforeAttempt = mutableListOf<Int>()
        val reconnects = AtomicInteger(0)

        val result = GrpcRetry.withRetry(
            callName = "viewHierarchy",
            sleepMs = { /* no-op */ },
            onBrokenPipe = {
                reconnectsBeforeAttempt.add(attempts.get())
                reconnects.incrementAndGet()
            },
        ) {
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) {
                throw Status.UNAVAILABLE
                    .withCause(IOException("Broken pipe"))
                    .asRuntimeException()
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(reconnects.get()).isEqualTo(1)
        // Reconnect must happen between attempt 1 (which failed) and attempt 2 (which retries).
        assertThat(reconnectsBeforeAttempt).containsExactly(1)
    }

    @Test
    internal fun `UNAVAILABLE with 'io exception' in message — also treated as broken pipe`() {
        val attempts = AtomicInteger(0)
        val reconnects = AtomicInteger(0)

        val result = GrpcRetry.withRetry(
            callName = "viewHierarchy",
            sleepMs = { /* no-op */ },
            onBrokenPipe = { reconnects.incrementAndGet() },
        ) {
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) {
                throw Status.UNAVAILABLE
                    .withDescription("io exception: connection closed")
                    .asRuntimeException()
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(reconnects.get()).isEqualTo(1)
    }

    @Test
    internal fun `plain UNAVAILABLE without IOException — retries but does not reconnect channel`() {
        val attempts = AtomicInteger(0)
        val reconnects = AtomicInteger(0)

        val result = GrpcRetry.withRetry(
            callName = "viewHierarchy",
            sleepMs = { /* no-op */ },
            onBrokenPipe = { reconnects.incrementAndGet() },
        ) {
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) {
                throw Status.UNAVAILABLE.withDescription("server busy").asRuntimeException()
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts.get()).isEqualTo(2)
        assertThat(reconnects.get()).isEqualTo(0)
    }

    @Test
    internal fun `total budget exceeded — stops retrying even if maxAttempts remain`() {
        val attempts = AtomicInteger(0)
        // Scripted clock readings, consumed in order:
        //   t=0     deadline init        → deadline = 30s
        //   t=10s   budget check before attempt 1  → 10 < 30, attempt 1 runs and fails
        //   t=25s   budget check before attempt 2  → 25 < 30, attempt 2 runs and fails
        //   t=40s   budget check before attempt 3  → 40 ≥ 30, stop and rethrow
        // maxAttempts is 5 but the budget cuts us off at 2 attempts.
        val timeline = ArrayDeque(mutableListOf(0L, 10_000L, 25_000L, 40_000L))
        val nowMs: () -> Long = { timeline.removeFirstOrNull() ?: 40_000L }

        val ex = assertThrows<StatusRuntimeException> {
            GrpcRetry.withRetry(
                callName = "deviceInfo",
                maxAttempts = 5,
                totalBudgetMs = 30_000L,
                nowMs = nowMs,
                sleepMs = { /* no-op */ },
            ) {
                attempts.incrementAndGet()
                throw Status.UNAVAILABLE.asRuntimeException()
            }
        }

        assertThat(ex.status.code).isEqualTo(Status.Code.UNAVAILABLE)
        assertThat(attempts.get()).isEqualTo(2)
    }
}
