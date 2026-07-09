package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IOSDeviceTest {

    @Test
    fun `connected tunnel is reachable`() {
        val properties = DeviceCtlResponse.ConnectionProperties(tunnelState = "connected")

        assertThat(properties.isReachable).isTrue()
    }

    @Test
    fun `paired wired device is reachable before lazy tunnel starts`() {
        val properties = DeviceCtlResponse.ConnectionProperties(
            tunnelState = "disconnected",
            transportType = "wired",
            pairingState = "paired",
        )

        assertThat(properties.isReachable).isTrue()
    }

    @Test
    fun `paired device with unavailable tunnel and no transport is not assumed reachable`() {
        val properties = DeviceCtlResponse.ConnectionProperties(
            tunnelState = "unavailable",
            transportType = null,
            pairingState = "paired",
        )

        assertThat(properties.isReachable).isFalse()
    }

    @Test
    fun `disconnected wireless or unpaired device is not reachable`() {
        assertThat(
            DeviceCtlResponse.ConnectionProperties(
                tunnelState = "disconnected",
                transportType = "wifi",
                pairingState = "paired",
            ).isReachable
        ).isFalse()
        assertThat(
            DeviceCtlResponse.ConnectionProperties(
                tunnelState = "disconnected",
                transportType = "wired",
                pairingState = "unpaired",
            ).isReachable
        ).isFalse()
    }

    @Test
    fun `only iOS family hardware is accepted`() {
        assertThat(DeviceCtlResponse.HardwareProperties("phone", platform = "iOS").isIOSFamily).isTrue()
        assertThat(DeviceCtlResponse.HardwareProperties("tablet", platform = "iPadOS").isIOSFamily).isTrue()
        assertThat(DeviceCtlResponse.HardwareProperties("watch", platform = "watchOS").isIOSFamily).isFalse()
        assertThat(DeviceCtlResponse.HardwareProperties("tv", platform = "tvOS").isIOSFamily).isFalse()
    }

    @Test
    fun `hardware platform is populated from devicectl JSON`() {
        val hardware = jacksonObjectMapper().readValue<DeviceCtlResponse.HardwareProperties>(
            """{"udid":"UDID-1","platform":"iOS","deviceType":"iPhone","reality":"physical"}"""
        )

        assertThat(hardware.udid).isEqualTo("UDID-1")
        assertThat(hardware.platform).isEqualTo("iOS")
        assertThat(hardware.isIOSFamily).isTrue()
    }
}
