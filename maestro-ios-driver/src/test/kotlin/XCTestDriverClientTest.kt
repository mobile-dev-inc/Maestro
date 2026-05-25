import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller
import xcuitest.XCTestClient

class XCTestDriverClientTest {

    class FakeXCTestInstaller(
        var channelAlive: Boolean = false,
        var versionMatch: Boolean = true,
    ) : XCTestInstaller {

        var startCount = 0
            private set
        var uninstallCount = 0
            private set

        override fun start(): XCTestClient {
            startCount++
            return XCTestClient("localhost", 22087)
        }

        override fun uninstall(): Boolean {
            uninstallCount++
            return true
        }

        override fun isChannelAlive(): Boolean = channelAlive
        override fun isVersionMatch(): Boolean = versionMatch
        override fun close() {}
    }

    @Test
    fun `restartXCTestRunner should reinstall when channel is alive but version mismatches`() {
        val installer = FakeXCTestInstaller(channelAlive = true, versionMatch = false)
        val driverClient = XCTestDriverClient(
            installer = installer,
            client = XCTestClient("localhost", 22087),
            reinstallDriver = false
        )

        driverClient.restartXCTestRunner()

        assertThat(installer.startCount).isEqualTo(1)
    }

    @Test
    fun `restartXCTestRunner should reuse session when channel is alive and version matches`() {
        val installer = FakeXCTestInstaller(channelAlive = true, versionMatch = true)
        val driverClient = XCTestDriverClient(
            installer = installer,
            client = XCTestClient("localhost", 22087),
            reinstallDriver = false
        )

        driverClient.restartXCTestRunner()

        assertThat(installer.startCount).isEqualTo(0)
        assertThat(installer.uninstallCount).isEqualTo(0)
    }

    @Test
    fun `restartXCTestRunner should honor explicit reinstall even when channel is alive and version matches`() {
        val installer = FakeXCTestInstaller(channelAlive = true, versionMatch = true)
        val driverClient = XCTestDriverClient(
            installer = installer,
            client = XCTestClient("localhost", 22087),
            reinstallDriver = true
        )

        driverClient.restartXCTestRunner()

        assertThat(installer.uninstallCount).isEqualTo(1)
        assertThat(installer.startCount).isEqualTo(1)
    }

    @Test
    fun `restartXCTestRunner should start fresh when channel is not alive`() {
        val installer = FakeXCTestInstaller(channelAlive = false)
        val driverClient = XCTestDriverClient(
            installer = installer,
            client = XCTestClient("localhost", 22087),
            reinstallDriver = false
        )

        driverClient.restartXCTestRunner()

        assertThat(installer.uninstallCount).isEqualTo(0)
        assertThat(installer.startCount).isEqualTo(1)
    }
}
