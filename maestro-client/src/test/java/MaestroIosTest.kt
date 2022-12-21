import io.grpc.ManagedChannelBuilder
import ios.IOSDevice
import ios.idb.IdbIOSDevice
import maestro.Maestro
import maestro.debuglog.IOSDriverLogger
import maestro.drivers.IOSDriver
import maestro.ios.XcUITestDriverImpl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createDirectories

@Disabled("Local testing only")
internal class MaestroIosTest {

    private lateinit var maestro: Maestro
    private val appId = "dev.mobile.DevToolTester"

    @BeforeEach
    fun setUp() {
        val channel = ManagedChannelBuilder.forAddress("localhost", 10882)
            .usePlaintext()
            .build()
        val deviceId = ""
        maestro = Maestro.ios(driver = IOSDriver(IdbIOSDevice(channel, deviceId), XcUITestDriverImpl(IOSDriverLogger(), deviceId)))
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
