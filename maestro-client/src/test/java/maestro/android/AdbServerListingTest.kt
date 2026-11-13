package maestro.android

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * dadb opens a transport to every row of `adb devices` while listing, so a single row that isn't
 * `device` (an emulator booting or shutting down, an unauthorized phone) made the whole listing
 * throw, and Maestro reported every device as not connected.
 */
class AdbServerListingTest {

    /**
     * Answers like an adb server: `host:devices`, and `host:transport:<serial>` + `host:features`.
     * A transport opens only for a `device` row; [dropped] serials are listed as `device` but fail
     * to open, like an emulator that shuts down between the two requests.
     */
    private class FakeAdbServer(
        private val devices: Map<String, String>,
        private val dropped: Set<String> = emptySet(),
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true) { socket.use(::serve) }
                }
            }
        }

        private fun serve(socket: Socket) {
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            fun read(length: Int) = String(ByteArray(length).also(input::readFully))
            fun reply(status: String, payload: String?) =
                output.write((status + (payload?.let { "%04x%s".format(it.length, it) } ?: "")).toByteArray())
            var transportOpen = false
            while (true) {
                val request = try {
                    read(read(4).toInt(16))
                } catch (_: IOException) {
                    return
                }
                when {
                    request == "host:devices" ->
                        return reply("OKAY", devices.entries.joinToString("") { "${it.key}\t${it.value}\n" })
                    request.startsWith("host:transport:") -> {
                        val serial = request.removePrefix("host:transport:")
                        val state = if (serial in dropped) "offline" else devices[serial]
                        if (state != "device") return reply("FAIL", "device ${state ?: "not found"}")
                        transportOpen = true
                        reply("OKAY", null)
                    }
                    request == "host:features" && transportOpen -> return reply("OKAY", "shell_v2,cmd")
                    else -> return reply("FAIL", "unsupported request $request")
                }
            }
        }

        override fun close() = server.close()
    }

    private fun listedSerials(vararg devices: Pair<String, String>, dropped: Set<String> = emptySet()): List<String> =
        FakeAdbServer(mapOf(*devices), dropped).use { server ->
            AndroidDeviceConnection.listFromAdbServer(server.port).map { connection ->
                connection.use { it.serial }
            }
        }

    @Test
    fun `lists every device when all are ready`() {
        assertThat(listedSerials("emulator-5560" to "device", "emulator-5562" to "device"))
            .containsExactly("emulator-5560", "emulator-5562")
    }

    @Test
    fun `a booting emulator listed as offline does not hide the ready one`() {
        assertThat(listedSerials("emulator-5560" to "device", "emulator-5562" to "offline"))
            .containsExactly("emulator-5560")
    }

    @Test
    fun `an unauthorized phone does not hide the ready emulator`() {
        assertThat(listedSerials("R58N12345" to "unauthorized", "emulator-5560" to "device"))
            .containsExactly("emulator-5560")
    }

    @Test
    fun `a device that drops between listing and connecting is skipped`() {
        assertThat(listedSerials("emulator-5560" to "device", "emulator-5562" to "device", dropped = setOf("emulator-5562")))
            .containsExactly("emulator-5560")
    }

    @Test
    fun `no ready device lists nothing instead of failing`() {
        assertThat(listedSerials("emulator-5562" to "offline")).isEmpty()
    }
}
