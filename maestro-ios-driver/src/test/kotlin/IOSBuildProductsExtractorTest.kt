import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertTrue
import util.IOSDeviceType
import xcuitest.installer.Context
import xcuitest.installer.IOSBuildProductsExtractor
import java.nio.file.Files
import java.nio.file.Path

class IOSBuildProductsExtractorTest {

    private lateinit var target: Path

    @Before
    fun setUp() {
        target = Files.createTempDirectory("test-path")
    }

    @Test
    fun `test drivers are extracted in expected structure for simulator`() {
        // when
        IOSBuildProductsExtractor(
            target,
            deviceType = IOSDeviceType.SIMULATOR,
            context = Context.CLI
        ).extract("driver-iphoneSimulator")

        // then
        val configFile = target.resolve("maestro-driver-ios-config.xctestrun")
        val appDir = target.resolve("Debug-iPhoneSimulator/maestro-driver-ios.app")
        val runnerDir = target.resolve("Debug-iPhoneSimulator/maestro-driver-iosUITests-Runner.app")

        assertTrue(Files.exists(configFile), "$configFile does not exist")
        assertTrue(Files.isDirectory(appDir), "$appDir is not a directory")
        assertTrue(Files.isDirectory(runnerDir), "$runnerDir is not a directory")
    }

    @After
    fun tearDown() {
        target.toFile().deleteRecursively()
    }
}