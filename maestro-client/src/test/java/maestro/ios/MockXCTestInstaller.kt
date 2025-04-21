package maestro.ios

import com.google.common.truth.Truth.assertThat
import xcuitest.XCTestClient
import xcuitest.installer.XCTestInstaller

class MockXCTestInstaller(
    private val simulator: Simulator,
) : XCTestInstaller {

    private var attempts = 0

    override fun start(): XCTestClient {
        attempts++
        for (i in 0..simulator.installationRetryCount) {
            assertThat(simulator.runningApps()).doesNotContain("dev.mobile.maestro-driver-iosUITests.xctrunner")
        }
        simulator.installXCTestDriver()
        return XCTestClient("localhost", 22807)
    }

    override fun uninstall(): Boolean {
        simulator.uninstallXCTestDriver()
        return true
    }

    override fun isChannelAlive(): Boolean {
        return simulator.isXCTestRunnerAlive()
    }

    override fun close() {
        simulator.uninstallXCTestDriver()
    }

    fun assertInstallationRetries(expectedRetries: Int) {
        assertThat(attempts).isEqualTo(expectedRetries)
    }

    data class Simulator(
        val installationRetryCount: Int = 0,
        val shouldInstall: Boolean = true
    ) {

        private val runningApps = mutableListOf<String>()

        fun runningApps() = runningApps

        fun isXCTestRunnerAlive() = runningApps.contains("dev.mobile.maestro-driver-iosUITests.xctrunner")

        fun uninstallXCTestDriver() = runningApps.clear()

        fun installXCTestDriver() = runningApps.add("dev.mobile.maestro-driver-iosUITests.xctrunner")
    }
}
