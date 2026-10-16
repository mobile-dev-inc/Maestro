package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import maestro.DeviceUnreachableException
import maestro.Maestro
import maestro.cli.model.FlowStatus
import maestro.cli.report.TestSuiteReporter
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the dynamic-scheduler worker's crash handling in [TestSuiteInteractor.runFromQueue].
 *
 * The regression these lock down: a device dying mid-flow used to be swallowed by runFlow's
 * `catch (Exception)` into a FlowStatus.ERROR, so the worker kept pulling flows onto the dead
 * device and every remaining flow on that shard failed (cascade). A transport death must instead
 * be re-routed via onDeviceCrash so a healthy worker re-runs the flow — while a genuine flow
 * failure must still be reported as a failure, not mistaken for a device crash.
 */
class TestSuiteInteractorTest {

    private fun launchFlow(): Path {
        val flow = Files.createTempFile("flow", ".yaml")
        Files.writeString(
            flow,
            """
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent()
        )
        return flow
    }

    private fun openedDriver(): FakeDriver {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement())
        driver.open()
        return driver
    }

    @Test
    fun `runFromQueue re-routes via onDeviceCrash when the device dies mid-flow`() {
        val driver = openedDriver()
        driver.addInstalledApp("com.example.app")
        // Transport death during launch — the exact cascade trigger seen in production.
        driver.launchError = DeviceUnreachableException("launchApp", RuntimeException("broken pipe"))

        val flow = launchFlow()
        val crashed = mutableListOf<Path>()
        val pending = AtomicInteger(1)

        Maestro(driver).use { maestro ->
            val interactor = TestSuiteInteractor(maestro = maestro, reporter = mockk(relaxed = true))
            val queue = Channel<Path>(Channel.UNLIMITED)
            runBlocking {
                queue.send(flow)
                interactor.runFromQueue(
                    flowQueue = queue,
                    pending = pending,
                    cancelled = AtomicBoolean(false),
                    onDeviceCrash = { crashed.add(it) },
                    env = emptyMap(),
                    debugOutputPath = Files.createTempDirectory("debug"),
                    deviceId = null,
                )
            }
        }

        // Device death is escalated to onDeviceCrash (flow can be re-run on a healthy device)...
        assertThat(crashed).containsExactly(flow)
        // ...and NOT consumed: pending stays 1 so a healthy worker still picks the flow up.
        assertThat(pending.get()).isEqualTo(1)
    }

    @Test
    fun `runFromQueue reports a normal flow failure without treating it as a device crash`() {
        // App not installed → launchApp throws MaestroException.UnableToLaunchApp: a test failure,
        // NOT a transport death, so it must be swallowed as a flow ERROR and consumed from the queue.
        val driver = openedDriver()

        val flow = launchFlow()
        val crashed = mutableListOf<Path>()
        val pending = AtomicInteger(1)

        val summary = Maestro(driver).use { maestro ->
            val interactor = TestSuiteInteractor(maestro = maestro, reporter = mockk(relaxed = true))
            val queue = Channel<Path>(Channel.UNLIMITED)
            runBlocking {
                queue.send(flow)
                interactor.runFromQueue(
                    flowQueue = queue,
                    pending = pending,
                    cancelled = AtomicBoolean(false),
                    onDeviceCrash = { crashed.add(it) },
                    env = emptyMap(),
                    debugOutputPath = Files.createTempDirectory("debug"),
                    deviceId = null,
                )
            }
        }

        assertThat(crashed).isEmpty()
        assertThat(pending.get()).isEqualTo(0)
        assertThat(summary.passed).isFalse()
        assertThat(summary.suites.first().flows.first().status).isEqualTo(FlowStatus.ERROR)
    }
}
