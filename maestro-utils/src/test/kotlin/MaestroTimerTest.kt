import maestro.utils.MaestroTimer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MaestroTimerTest {

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc { _, ms -> Thread.sleep(ms) }
    }

    @Test
    fun `withTimeout should return result within timeout`() {
        val result = MaestroTimer.withTimeout(1000) {
            "Success"
        }

        assertEquals("Success", result)
    }

    @Test
    fun `withTimeout should return null if body is null`() {
        val result = MaestroTimer.withTimeout(1000) {
            null
        }

        assertNull(result)
    }

    @Test
    fun `retryUntilTrue should return true if block succeeds within timeout`() {
        val result = MaestroTimer.retryUntilTrue(1000) {
            true
        }

        assertTrue(result)
    }

    @Test
    fun `retryUntilTrue should return false if block fails within timeout`() {
        val result = MaestroTimer.retryUntilTrue(100) {
            false
        }

        assertFalse(result)
    }

    @Test
    fun `retryUntilTrue should handle exceptions and continue retrying`() {
        var attempts = 0
        val result = MaestroTimer.retryUntilTrue(1000, 100, { }) {
            attempts++
            if (attempts < 3) throw Exception("Test exception")
            true
        }

        assertTrue(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `setTimerFunc should change the sleep function`() {
        var sleepCalled = false
        MaestroTimer.setTimerFunc { _, _ -> sleepCalled = true }
        MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 100)

        assertTrue(sleepCalled)
    }
}