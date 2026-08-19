package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The CLI-free provisioning entry point the worker (and any non-CLI consumer) uses: it depends only
 * on `maestro-orchestra` types (no `maestro-cli`) and drives the same build -> connect -> close
 * lifecycle the CLI/MCP session managers already use.
 */
class DeviceCoreProvisioningTest {

    private fun realDriverOver(provider: FakeDeviceProvider): () -> DeviceCoreDriver =
        { RealDeviceCoreDriver(providerFactory = { provider }) }

    @Test
    fun `connect threads platform + serial + appId into a connected driver the caller owns`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }

        val driver = DeviceCoreProvisioning.connect(
            platform = Platform.ANDROID,
            serial = "emulator-5554",
            appId = "com.example.app",
            driverFactory = realDriverOver(provider),
        )

        assertThat(provider.connectCount).isEqualTo(1)
        assertThat(provider.lastConnectedTarget?.serial).isEqualTo("emulator-5554")
        // connect() hands back an open driver — the caller owns close().
        assertThat(provider.closed).isFalse()

        driver.close()
        assertThat(provider.closed).isTrue()
    }

    @Test
    fun `withSession connects, runs the block, and closes afterwards`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        var sawConnected = false

        val result = DeviceCoreProvisioning.withSession(
            platform = Platform.IOS,
            serial = "sim-udid",
            appId = "com.example.app",
            driverFactory = realDriverOver(provider),
        ) { driver ->
            sawConnected = provider.connectCount == 1 && !provider.closed
            driver.launchApp("com.example.app")
            "ran"
        }

        assertThat(result).isEqualTo("ran")
        assertThat(sawConnected).isTrue()
        assertThat(provider.launchedApps).containsExactly("com.example.app")
        assertThat(provider.closed).isTrue()
    }

    @Test
    fun `withSession closes the driver even when the block throws`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }

        assertThrows<IllegalStateException> {
            DeviceCoreProvisioning.withSession(
                platform = Platform.ANDROID,
                driverFactory = realDriverOver(provider),
            ) { error("boom") }
        }

        assertThat(provider.closed).isTrue()
    }
}
