package maestro.cli.android

import maestro.Maestro
import maestro.MaestroException
import maestro.cli.report.TestDebugReporter
import maestro.drivers.AndroidDriver
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import okio.sink
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

@Tag("IntegrationTest")
class AndroidIntegrationTest {

    private lateinit var driver: AndroidDriver
    private lateinit var maestro: Maestro

    companion object {

        @BeforeAll
        @JvmStatic
        fun setupBeforeAll() {
            TestDebugReporter.install(
                flattenDebugOutput = false,
                printToConsole = false,
            )
        }
    }

    @BeforeEach
    fun setup() {
        val dadb = AndroidDeviceProvider().local()
        driver = AndroidDriver(dadb)
        maestro = Maestro.android(driver)
    }

    @Test
    fun `test setting multiple location command works as expected`() {
        // given
        val locationFlow = Paths.get("./src/test/resources/location/assert_multiple_locations.yaml")

        // then
        assertDoesNotThrow {
            runFlow(locationFlow, "assert_multiple_locations")
        }
    }

    @Test
    fun `test travel command works as expected`() {
        // given
        val travelCommandFlow = Paths.get("./src/test/resources/travel/assert_travel_command.yaml")

        // then
        assertDoesNotThrow {
            runFlow(travelCommandFlow, "assert_travel_command")
        }
    }

    private fun runFlow(flowFile: Path, flowName: String) {
        val commands = YamlCommandReader.readCommands(flowFile)
        val orchestra = Orchestra(maestro)

        try {
            runBlocking {
                orchestra.runFlow(commands)
            }
        } catch (exception: MaestroException) {
            val debugOutput =  TestDebugReporter.getDebugOutputPath()
            val screenshotFile = debugOutput.resolve(flowName + "_failure.png")
            maestro.takeScreenshot(screenshotFile.sink(), true)

            throw exception
        } finally {
            maestro.close()
        }
    }

}