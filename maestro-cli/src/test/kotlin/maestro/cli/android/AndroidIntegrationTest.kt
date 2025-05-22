package maestro.cli.android

import maestro.Maestro
import maestro.MaestroException
import maestro.cli.report.FlowDebugOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.MaestroCommandRunner
import maestro.drivers.AndroidDriver
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import okio.sink
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import kotlin.io.path.pathString

@Tag("IntegrationTest")
class AndroidIntegrationTest {

    private lateinit var driver: AndroidDriver
    private lateinit var maestro: Maestro

    @BeforeEach
    fun setup() {
        val dadb = AndroidDeviceProvider().local()
        driver = AndroidDriver(dadb)
        maestro = Maestro.android(driver)
    }

    @Test
    fun `test setting multiple location command works as expected`() {
        // given
        TestDebugReporter.install(
            flattenDebugOutput = false,
            printToConsole = false,
        )
        val commands = YamlCommandReader.readCommands(
            Paths.get("./src/test/resources/location/assert_multiple_locations.yaml")
        )

        // when
        val orchestra = Orchestra(maestro)

        // then
        assertDoesNotThrow {
            maestro.use {
                try {
                    orchestra.runFlow(
                        commands
                    )
                } catch (exception: MaestroException) {
                    val failingScreenshot = FileOutputStream(
                        TestDebugReporter.getDebugOutputPath().pathString + "/assert_multiple_locations.png"
                    )
                    it.takeScreenshot(failingScreenshot.sink(), true)
                    throw exception
                }
            }
        }
    }

}