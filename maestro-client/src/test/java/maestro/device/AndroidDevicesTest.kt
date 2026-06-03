package maestro.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class AndroidDevicesTest {

    @Test
    fun `parses host and port from dadb toString`() {
        val descriptor = AndroidDevices.parseDescriptor("localhost:6520")

        assertThat(descriptor).isEqualTo(
            AndroidDeviceDescriptor(id = "localhost:6520", host = "localhost", port = 6520)
        )
    }

    @Test
    fun `parses ipv4 host and port`() {
        val descriptor = AndroidDevices.parseDescriptor("127.0.0.1:5555")

        assertThat(descriptor).isEqualTo(
            AndroidDeviceDescriptor(id = "127.0.0.1:5555", host = "127.0.0.1", port = 5555)
        )
    }

    @Test
    fun `id round-trips to host and port`() {
        val descriptor = AndroidDevices.parseDescriptor("emulator-host:7001")!!

        assertThat("${descriptor.host}:${descriptor.port}").isEqualTo(descriptor.id)
    }

    @Test
    fun `returns null when no colon`() {
        assertThat(AndroidDevices.parseDescriptor("emulator-5554")).isNull()
    }

    @Test
    fun `returns null when port is not numeric`() {
        assertThat(AndroidDevices.parseDescriptor("localhost:abc")).isNull()
    }

    @Test
    fun `returns null when host is empty`() {
        assertThat(AndroidDevices.parseDescriptor(":6520")).isNull()
    }
}
