package util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.TimeUnit

class DeviceCtlProcessTest {

    @Test
    fun `successful listing returns JSON and cleans temporary output`() {
        val process = mockk<Process>()
        every { process.waitFor(1, TimeUnit.MILLISECONDS) } returns true
        every { process.exitValue() } returns 0
        lateinit var jsonOutput: File
        val deviceCtl = DeviceCtlProcess(
            processStarter = { command, _ ->
                jsonOutput = File(command[3]).apply { writeText("""{"result":{"devices":[]}}""") }
                process
            },
            waitTimeoutMs = 1,
        )

        val response = deviceCtl.devicectlDevicesJson()

        assertThat(response).contains("\"devices\":[]")
        assertThat(jsonOutput.exists()).isFalse()
    }

    @Test
    fun `timeout stops devicectl and returns actionable failure`() {
        val process = mockk<Process>()
        every { process.waitFor(1, TimeUnit.MILLISECONDS) } returns false
        every { process.isAlive } returns true
        every { process.destroy() } returns Unit
        every { process.waitFor(3, TimeUnit.SECONDS) } returns false
        every { process.destroyForcibly() } returns process
        val deviceCtl = DeviceCtlProcess(
            processStarter = { _, _ -> process },
            waitTimeoutMs = 1,
        )

        val error = assertThrows<IllegalStateException> {
            deviceCtl.devicectlDevicesJson()
        }

        assertThat(error).hasMessageThat().contains("Timed out")
        assertThat(error).hasMessageThat().contains("connected over USB")
        verify { process.destroy() }
        verify { process.destroyForcibly() }
    }

    @Test
    fun `interruption stops devicectl and restores interrupt status`() {
        val process = mockk<Process>()
        every { process.waitFor(1, TimeUnit.MILLISECONDS) } throws InterruptedException("cancelled")
        every { process.isAlive } returns true
        every { process.destroy() } returns Unit
        every { process.waitFor(3, TimeUnit.SECONDS) } returns true
        val deviceCtl = DeviceCtlProcess(
            processStarter = { _, _ -> process },
            waitTimeoutMs = 1,
        )

        try {
            val error = assertThrows<IllegalStateException> {
                deviceCtl.devicectlDevicesJson()
            }

            assertThat(error).hasMessageThat().contains("Interrupted")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify { process.destroy() }
        } finally {
            Thread.interrupted()
        }
    }
}
