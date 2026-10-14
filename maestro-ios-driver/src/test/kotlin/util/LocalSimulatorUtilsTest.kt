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
}
