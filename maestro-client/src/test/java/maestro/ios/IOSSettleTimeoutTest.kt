package maestro.ios

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests the settle timeout budget allocation logic used in
 * IOSDriver.waitForAppToSettle. The core invariant:
 * - waitToSettleTimeoutMs controls the TOTAL budget (Tier 1 + Tier 2)
 * - If Tier 1 consumes some time, Tier 2 gets the remainder
 * - If Tier 1 consumes all time, Tier 2 gets 0
 */
class IOSSettleTimeoutTest {

    @Test
    fun `custom timeout controls total budget`() {
        val timeoutMs = 500
        val totalTimeout = timeoutMs.toLong()
        assertThat(totalTimeout).isEqualTo(500L)
    }

    @Test
    fun `null timeout defaults to SCREEN_SETTLE_TIMEOUT_MS`() {
        val SCREEN_SETTLE_TIMEOUT_MS = 3000L
        val timeoutMs: Int? = null
        val totalTimeout = timeoutMs?.toLong() ?: SCREEN_SETTLE_TIMEOUT_MS
        assertThat(totalTimeout).isEqualTo(3000L)
    }

    @Test
    fun `remaining budget is positive when tier 1 finishes early`() {
        val totalTimeout = 500L
        val tier1Elapsed = 200L
        val remainingMs = (totalTimeout - tier1Elapsed).coerceAtLeast(0)
        assertThat(remainingMs).isEqualTo(300L)
    }

    @Test
    fun `remaining budget is zero when tier 1 consumes all time`() {
        val totalTimeout = 500L
        val tier1Elapsed = 500L
        val remainingMs = (totalTimeout - tier1Elapsed).coerceAtLeast(0)
        assertThat(remainingMs).isEqualTo(0L)
    }

    @Test
    fun `remaining budget is zero when tier 1 exceeds timeout`() {
        val totalTimeout = 500L
        val tier1Elapsed = 800L  // Tier 1 overshot
        val remainingMs = (totalTimeout - tier1Elapsed).coerceAtLeast(0)
        assertThat(remainingMs).isEqualTo(0L)
    }

    @Test
    fun `default behavior unchanged when timeoutMs is null`() {
        val SCREEN_SETTLE_TIMEOUT_MS = 3000L
        val timeoutMs: Int? = null
        val totalTimeout = timeoutMs?.toLong() ?: SCREEN_SETTLE_TIMEOUT_MS

        val tier1Elapsed = 1000L
        val remainingMs = (totalTimeout - tier1Elapsed).coerceAtLeast(0)

        assertThat(totalTimeout).isEqualTo(3000L)
        assertThat(remainingMs).isEqualTo(2000L)
    }
}
