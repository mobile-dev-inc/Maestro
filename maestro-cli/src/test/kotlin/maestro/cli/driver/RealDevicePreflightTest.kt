package maestro.cli.driver

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.cli.CliError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.IProxyHelper
import util.IProxyNotFoundException
import java.util.concurrent.TimeUnit

class RealDevicePreflightTest {

    @Test
    fun `iproxy capability failure is surfaced with installation guidance`() {
        val error = assertThrows<CliError> {
            RealDevicePreflight.checkIProxy {
                throw IProxyNotFoundException(
                    "The installed iproxy does not support --source. ${IProxyHelper.INSTALL_HINT}"
                )
            }
        }

        assertThat(error).hasMessageThat().contains("secure, loopback-only USB port forwarding")
        assertThat(error).hasMessageThat().contains("--source")
        assertThat(error).hasMessageThat().contains("libusbmuxd 2.0.2 or newer")
    }

    @Test
    fun `interrupted devicectl preflight stops its child and preserves interruption`() {
        val process = mockk<Process>()
        every { process.waitFor(10, TimeUnit.SECONDS) } throws InterruptedException("cancelled")
        every { process.isAlive } returns true
        every { process.destroy() } returns Unit
        every { process.waitFor(3, TimeUnit.SECONDS) } returns true

        try {
            assertThrows<InterruptedException> {
                RealDevicePreflight.checkDevicectl { process }
            }

            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(exactly = 1) { process.destroy() }
        } finally {
            Thread.interrupted()
        }
    }
}
