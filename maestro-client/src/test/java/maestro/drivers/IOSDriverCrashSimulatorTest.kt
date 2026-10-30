package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import maestro.device.AppCrashReport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit

/**
 * [IOSDriver.findAppCrash] against a real iOS simulator and a real app: the whole of the driver's
 * crash detection, from the simulator's record of how the process ended to the crash report macOS
 * writes for it. [IOSDriverTest] covers the same function with both of those faked.
 *
 * Nothing drives the UI, so no XCTest runner is involved: the e2e demo app (`e2e/demo_app`), which
 * must be installed on the simulator, ends itself on a launch argument.
 *
 * Skipped unless MAESTRO_TEST_SIMULATOR_UDID names a booted simulator:
 *
 *   (cd e2e/demo_app && flutter build ios --simulator && xcrun simctl install <udid> build/ios/iphonesimulator/Runner.app)
 *   MAESTRO_TEST_SIMULATOR_UDID=<udid> ./gradlew :maestro-client:test --tests '*IOSDriverCrashSimulatorTest'
 */
@EnabledIfEnvironmentVariable(named = "MAESTRO_TEST_SIMULATOR_UDID", matches = ".+")
class IOSDriverCrashSimulatorTest {

    // findAppCrash needs only the simulator's id from the device; the finders behind it are the real ones.
    private val driver = IOSDriver(mockk<IOSDevice>(relaxed = true) { every { deviceId } returns simulatorId })

    @Test
    fun `an app that crashes is reported as a crash, with the report macOS wrote for it`() {
        val flowStart = System.currentTimeMillis()
        launchDemoApp("-crashScreen", "crash") // the app ends itself with a Swift runtime failure
        awaitAppEnded()

        val crash = driver.findAppCrash(DEMO_APP, flowStart)

        assertThat(crash).isNotNull()
        assertThat(crash!!.message).isEqualTo("App crashed (SIGTRAP)")
        // The report is the .ips of this app, not the "no report was written" text.
        assertThat(crash.content).contains("\"bundleID\":\"$DEMO_APP\"")
    }

    @Test
    fun `an app that is closed on purpose is not reported as a crash`() {
        val flowStart = System.currentTimeMillis()
        launchDemoApp()
        run("xcrun", "simctl", "terminate", simulatorId, DEMO_APP)
        awaitAppEnded()

        assertThat(driver.findAppCrash(DEMO_APP, flowStart)).isNull()
    }

    @Test
    fun `an app that exits normally is not reported as a crash`() {
        val flowStart = System.currentTimeMillis()
        launchDemoApp("-crashScreen", "exit")
        awaitAppEnded()

        assertThat(driver.findAppCrash(DEMO_APP, flowStart)).isNull()
    }

    @Test
    fun `an app that is still running is not reported as a crash`() {
        val flowStart = System.currentTimeMillis()
        launchDemoApp()

        assertThat(driver.findAppCrash(DEMO_APP, flowStart)).isNull()
    }

    private fun launchDemoApp(vararg arguments: String) {
        run("xcrun", "simctl", "launch", "--terminate-running-process", simulatorId, DEMO_APP, *arguments)
    }

    /** Waits until the demo app's process is gone, however it ended. */
    private fun awaitAppEnded() {
        val deadline = System.currentTimeMillis() + APP_END_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // launchd lists the simulator's running apps as UIKitApplication:<bundle id>[...].
            if (!run("xcrun", "simctl", "spawn", simulatorId, "launchctl", "list").contains("UIKitApplication:$DEMO_APP[")) return
            Thread.sleep(250)
        }
        error("$DEMO_APP was still running on $simulatorId after ${APP_END_TIMEOUT_MS / 1000}s")
    }

    companion object {
        private const val DEMO_APP = "com.example.example"
        private const val APP_END_TIMEOUT_MS = 30_000L

        private val simulatorId: String get() = System.getenv("MAESTRO_TEST_SIMULATOR_UDID")

        @JvmStatic
        @BeforeAll
        fun requireDemoApp() {
            val installed = ProcessBuilder("xcrun", "simctl", "get_app_container", simulatorId, DEMO_APP)
                .redirectErrorStream(true).start().waitFor() == 0
            check(installed) {
                "The e2e demo app ($DEMO_APP) is not installed on simulator $simulatorId. " +
                    "Build and install it: cd e2e/demo_app && flutter build ios --simulator && " +
                    "xcrun simctl install $simulatorId build/ios/iphonesimulator/Runner.app"
            }
        }

        private fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0) {
                "${command.joinToString(" ")} failed: $output"
            }
            return output
        }
    }
}
