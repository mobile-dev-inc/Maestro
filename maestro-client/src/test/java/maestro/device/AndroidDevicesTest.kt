package maestro.device

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AndroidDevicesTest {

    @Test
    fun `resolves a device by its enumerated serial id`() {
        // A serial-shaped id (no colon) — the exact shape the old parseDescriptor dropped.
        val match = mockk<Dadb>(relaxed = true)
        every { match.toString() } returns "emulator-5554"
        val other = mockk<Dadb>(relaxed = true)
        every { other.toString() } returns "emulator-5556"

        val resolved = AndroidDevices.resolveDadb(
            deviceId = "emulator-5554",
            list = { listOf(other, match) },
        )

        assertThat(resolved === match).isTrue()
        verify(exactly = 0) { match.close() }
        verify { other.close() } // non-matched connections are released
    }

    @Test
    fun `resolves the first device when no id is given`() {
        val first = mockk<Dadb>(relaxed = true)
        every { first.toString() } returns "emulator-5554"
        val second = mockk<Dadb>(relaxed = true)
        every { second.toString() } returns "emulator-5556"

        val resolved = AndroidDevices.resolveDadb(deviceId = null, list = { listOf(first, second) })

        assertThat(resolved === first).isTrue()
        verify(exactly = 0) { first.close() }
        verify { second.close() }
    }

    @Test
    fun `throws when no enumerated device matches the id`() {
        val other = mockk<Dadb>(relaxed = true)
        every { other.toString() } returns "emulator-5556"

        val error = assertThrows<IllegalStateException> {
            AndroidDevices.resolveDadb(deviceId = "emulator-5554", list = { listOf(other) })
        }

        assertThat(error).hasMessageThat().contains("emulator-5554")
        verify { other.close() }
    }

    @Test
    fun `throws when no devices are connected`() {
        val error = assertThrows<IllegalStateException> {
            AndroidDevices.resolveDadb(deviceId = "emulator-5554", list = { emptyList() })
        }

        assertThat(error).hasMessageThat().contains("emulator-5554")
    }
}
