package maestro.orchestra.android

import dadb.Dadb
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Paths

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
        val commands = YamlCommandReader.readCommands(
            Paths.get("./src/test/resources/integration/location/assert_multiple_locations.yaml")
        )

        // when
        val orchestra = Orchestra(maestro)

        // then
        assertDoesNotThrow {
            maestro.use {
                orchestra.runFlow(
                    commands
                )
            }
        }
    }


}