package maestro.android

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class AdbServerDeviceDiscoveryTest {

    @Test
    fun `query preserves device states and writes the adb host request`() {
        val payload = """
            healthy-serial	device
            unauthorized-serial	unauthorized
            offline-serial	offline
        """.trimIndent() + "\n"
        val output = ByteArrayOutputStream()

        val devices = AdbServerDeviceDiscovery.query(
            input = DataInputStream(ByteArrayInputStream(okayResponse(payload))),
            output = DataOutputStream(output),
        )

        assertThat(devices).containsExactly(
            AdbServerDevice("healthy-serial", "device"),
            AdbServerDevice("unauthorized-serial", "unauthorized"),
            AdbServerDevice("offline-serial", "offline"),
        ).inOrder()
        assertThat(output.toString(StandardCharsets.UTF_8))
            .isEqualTo("000chost:devices")
    }

    @Test
    fun `connectOnlineDevices ignores unusable states and isolates one connection failure`() {
        val attempted = mutableListOf<String>()
        val devices = listOf(
            AdbServerDevice("healthy-1", "device"),
            AdbServerDevice("unauthorized", "unauthorized"),
            AdbServerDevice("raced-offline", "device"),
            AdbServerDevice("offline", "offline"),
            AdbServerDevice("healthy-2", "device"),
        )

        val connected = AdbServerDeviceDiscovery.connectOnlineDevices(
            devices = devices,
            connect = { serial ->
                attempted += serial
                if (serial == "raced-offline") throw IOException("transport changed")
                serial
            },
        )

        assertThat(attempted).containsExactly("healthy-1", "raced-offline", "healthy-2").inOrder()
        assertThat(connected).containsExactly("healthy-1", "healthy-2").inOrder()
    }

    @Test
    fun `query exposes an adb server failure`() {
        val message = "device unauthorized"

        val error = assertThrows<IOException> {
            AdbServerDeviceDiscovery.query(
                input = DataInputStream(ByteArrayInputStream(failResponse(message))),
                output = DataOutputStream(ByteArrayOutputStream()),
            )
        }

        assertThat(error).hasMessageThat().contains(message)
    }

    private fun okayResponse(payload: String): ByteArray =
        "OKAY${encoded(payload)}".toByteArray(StandardCharsets.UTF_8)

    private fun failResponse(message: String): ByteArray =
        "FAIL${encoded(message)}".toByteArray(StandardCharsets.UTF_8)

    private fun encoded(value: String): String =
        String.format("%04x", value.toByteArray(StandardCharsets.UTF_8).size) + value
}
