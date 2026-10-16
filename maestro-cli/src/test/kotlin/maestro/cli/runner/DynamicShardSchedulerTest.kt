package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceUnreachableException
import maestro.Maestro
import maestro.cli.CliError
import maestro.cli.model.TestExecutionSummary
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.ExecutionPlan
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.FlowSequence
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class DynamicShardSchedulerTest {

    private val appId = "com.example.app"

    private class SlowDriver(private val launchMillis: Long) : FakeDriver() {
        override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
            Thread.sleep(launchMillis)
            super.launchApp(appId, launchArguments)
        }
    }

    private fun flows(count: Int): List<Path> {
        val dir = Files.createTempDirectory("flows")
        return (1..count).map { index ->
            dir.resolve("flow-$index.yaml").also {
                Files.writeString(it, "appId: $appId\n---\n- launchApp\n")
            }
        }
    }

    private fun driver(launchMillis: Long = 0, launchError: Throwable? = null): FakeDriver {
        val driver = if (launchMillis > 0) SlowDriver(launchMillis) else FakeDriver()
        driver.setLayout(FakeLayoutElement())
        driver.addInstalledApp(appId)
        driver.launchError = launchError
        driver.open()
        return driver
    }

    private fun connected(deviceId: String) = Device.Connected(
        instanceId = deviceId,
        deviceSpec = DeviceSpec.Android.DEFAULT,
        description = deviceId,
        platform = Platform.ANDROID,
        deviceType = Device.DeviceType.EMULATOR,
    )

    private fun scheduler(
        flows: List<Path>,
        drivers: Map<String, () -> FakeDriver>,
        minHealthyDevices: Int = 1,
    ) = DynamicShardScheduler(
        plan = ExecutionPlan(flowsToRun = flows, sequence = FlowSequence(emptyList())),
        deviceIds = drivers.keys.toList(),
        minHealthyDevices = minHealthyDevices,
        env = emptyMap(),
        debugOutputPath = Files.createTempDirectory("debug"),
        host = null,
        port = null,
        teamId = null,
        platform = null,
        isHeadless = true,
        screenSize = null,
        reinstallDriver = false,
        reporter = mockk(relaxed = true),
        captureSteps = false,
        sessionOpener = { deviceId, block ->
            Maestro(drivers.getValue(deviceId)()).use { block(it, connected(deviceId)) }
        },
    )

    private fun flowsByDevice(summaries: List<TestExecutionSummary>): Map<String, List<String>> =
        summaries.flatMap { it.suites }
            .groupBy({ it.deviceName.orEmpty() }, { suite -> suite.flows.map { it.name } })
            .mapValues { (_, names) -> names.flatten() }

    @Test
    fun `every flow runs exactly once across the workers`() {
        val flows = flows(9)
        val summaries = runBlocking {
            scheduler(flows, mapOf("d1" to { driver() }, "d2" to { driver() }, "d3" to { driver() })).run()
        }

        val executed = flowsByDevice(summaries).values.flatten()
        assertThat(executed).containsExactlyElementsIn((1..9).map { "flow-$it" })
        assertThat(summaries.sumOf { it.passedCount ?: 0 }).isEqualTo(9)
    }

    @Test
    fun `a faster device pulls more flows from the shared queue`() {
        val summaries = runBlocking {
            scheduler(flows(12), mapOf("fast" to { driver() }, "slow" to { driver(launchMillis = 400) })).run()
        }

        val byDevice = flowsByDevice(summaries)
        assertThat(byDevice.getValue("fast").size).isGreaterThan(byDevice.getValue("slow").size)
        assertThat(byDevice.values.flatten()).hasSize(12)
    }

    @Test
    fun `flows of a crashed device are re-enqueued and run by a healthy one`() {
        val crash = DeviceUnreachableException("launchApp", RuntimeException("broken pipe"))
        val summaries = runBlocking {
            scheduler(flows(6), mapOf("healthy" to { driver() }, "dying" to { driver(launchError = crash) })).run()
        }

        val byDevice = flowsByDevice(summaries)
        assertThat(byDevice["dying"].orEmpty()).isEmpty()
        assertThat(byDevice.getValue("healthy")).containsExactlyElementsIn((1..6).map { "flow-$it" })
    }

    @Test
    fun `run is cancelled when healthy devices drop below the minimum`() {
        val crash = DeviceUnreachableException("launchApp", RuntimeException("broken pipe"))
        val error = assertThrows<CliError> {
            runBlocking {
                scheduler(
                    flows(4),
                    mapOf("d1" to { driver(launchError = crash) }, "d2" to { driver(launchError = crash) }),
                    minHealthyDevices = 2,
                ).run()
            }
        }

        assertThat(error).hasMessageThat().contains("minimum is 2")
    }

    @Test
    fun `an assertion failure is reported once and not re-enqueued`() {
        val failing = flows(1).single().also {
            Files.writeString(it, "appId: com.not.installed\n---\n- launchApp\n")
        }
        val summaries = runBlocking {
            scheduler(listOf(failing), mapOf("d1" to { driver() }, "d2" to { driver() })).run()
        }

        assertThat(flowsByDevice(summaries).values.flatten()).containsExactly("flow-1")
        assertThat(summaries.sumOf { it.passedCount ?: 0 }).isEqualTo(0)
    }

    @Test
    fun `an empty queue finishes without errors`() {
        val summaries = runBlocking {
            scheduler(emptyList(), mapOf("d1" to { driver() })).run()
        }

        assertThat(flowsByDevice(summaries).values.flatten()).isEmpty()
    }
}
