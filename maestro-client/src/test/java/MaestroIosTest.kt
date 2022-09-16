import maestro.Maestro
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createDirectories

@Disabled("Local testing only")
internal class MaestroIosTest {

    private lateinit var maestro: Maestro
    private val appId = "dev.mobile.DevToolTester"

    @BeforeEach
    fun setUp() {
        maestro = Maestro.ios("localhost", 10882)
    }

    @Test
    fun pull() {
        val file = File("/tmp/state/app.state").also {
            it.toPath().parent.createDirectories()
        }

        maestro.stopApp(appId)
        maestro.pullAppState(appId, file)
    }

    @Test
    fun push() {
        val file = File("/tmp/state/app.state")
        maestro.stopApp(appId)
        maestro.pushAppState(appId, file)
        maestro.launchApp(appId)
    }
}
