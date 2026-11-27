package maestro.cli.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class McpMaestroSessionManagerTest {

    @Test
    fun `reserveXCTestPort never hands out the same port twice, even when called concurrently`() {
        val manager = McpMaestroSessionManager()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ports = executor.invokeAll(List(64) { Callable { manager.reserveXCTestPort() } })
                .map { it.get() }

            assertThat(ports.toSet()).hasSize(ports.size)
            assertThat(ports).doesNotContain(22087)
        } finally {
            executor.shutdownNow()
            manager.close()
        }
    }
}
