import io.grpc.Internal
import maestro.Maestro
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.io.path.createDirectories

@Ignore("Local testing only")
internal class MaestroIosTest {

    private lateinit var maestro: Maestro
    private val appId = "dev.mobile.DevToolTester"

    @Before
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
