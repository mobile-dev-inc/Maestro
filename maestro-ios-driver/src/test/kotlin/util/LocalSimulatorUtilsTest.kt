package util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalSimulatorUtilsTest {

    private val commands = mutableListOf<List<String>>()

    @BeforeEach
    fun setUp() {
        mockkObject(CommandLineUtils)
        every { CommandLineUtils.runCommand(any(), any(), any(), any()) } answers {
            commands += firstArg<List<String>>()
            mockk(relaxed = true)
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(CommandLineUtils)
    }

    @Test
    fun `maps each orientation to the value Device Hub's rotate event carries`() {
        assertThat(LocalSimulatorUtils.deviceHubOrientation("portrait")).isEqualTo("portrait")
        assertThat(LocalSimulatorUtils.deviceHubOrientation("landscapeLeft")).isEqualTo("landscape-left")
        assertThat(LocalSimulatorUtils.deviceHubOrientation("landscapeRight")).isEqualTo("landscape-right")
    }

    @Test
    fun `upside down has no Device Hub value`() {
        assertThat(LocalSimulatorUtils.deviceHubOrientation("upsideDown")).isNull()
    }

    @Test
    fun `every mapped value is one the bundled helper accepts`() {
        val source = LocalSimulatorUtils::class.java.getResourceAsStream("/orient_helper.c")!!
            .bufferedReader().use { it.readText() }
        val accepted = Regex("""kOrientations\[] = \{([^}]*)}""").find(source)!!.groupValues[1]
            .split(",").map { it.trim().removeSurrounding("\"") }

        listOf("portrait", "landscapeLeft", "landscapeRight").forEach {
            assertThat(accepted).contains(LocalSimulatorUtils.deviceHubOrientation(it))
        }
    }

    @Test
    fun `setOrientation spawns the helper inside the simulator with the Device Hub value`() {
        LocalSimulatorUtils(TempFileHandler()).setOrientation("SIM-ID", "landscapeLeft")

        val spawn = commands.single { it.take(3) == listOf("xcrun", "simctl", "spawn") }
        assertThat(spawn[3]).isEqualTo("SIM-ID")
        assertThat(spawn[4]).endsWith("/orient_helper")
        assertThat(spawn[5]).isEqualTo("landscape-left")
    }

    @Test
    fun `setOrientation to upside down runs nothing`() {
        LocalSimulatorUtils(TempFileHandler()).setOrientation("SIM-ID", "upsideDown")

        assertThat(commands).isEmpty()
    }

    @Test
    fun `a foldable with only its outer display lit is closed`() {
        assertThat(LocalSimulatorUtils.isFoldOpen(displaysJson(outerActive = true, innerActive = false))).isFalse()
    }

    @Test
    fun `a foldable with its inner display lit is open`() {
        assertThat(LocalSimulatorUtils.isFoldOpen(displaysJson(outerActive = false, innerActive = true))).isTrue()
    }

    @Test
    fun `a single display is not a foldable`() {
        val json = """{"result": {"displays": [{"primary": true, "active": true, "displayId": 1}]}}"""

        assertThat(LocalSimulatorUtils.isFoldOpen(json)).isNull()
    }

    @Test
    fun `output that is not JSON reads as no fold`() {
        assertThat(LocalSimulatorUtils.isFoldOpen("Error: Unknown option '--json-output'")).isNull()
    }

    // The shape `xcrun devicectl device info displays --json-output -` prints for an iPhone Duo (Xcode 27.1).
    private fun displaysJson(outerActive: Boolean, innerActive: Boolean) = """
        {
          "info": {"commandType": "devicectl.device.info.displays", "outcome": "success"},
          "result": {
            "displays": [
              {"active": $outerActive, "displayId": 1, "name": "LCD", "primary": true},
              {"active": $innerActive, "displayId": 3, "name": "LCD-1", "primary": false}
            ]
          }
        }
    """.trimIndent()
}
