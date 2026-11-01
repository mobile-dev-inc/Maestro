import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import util.IOSDeviceType
import xcuitest.installer.Context
import xcuitest.installer.IOSBuildProductsExtractor
import java.nio.file.Files
import java.nio.file.Path

class IOSBuildProductsExtractorTest {

    private lateinit var target: Path

    @BeforeEach
    fun setUp() {
        target = Files.createTempDirectory("test-path")
    }

    @Test
    fun `test drivers are extracted in expected structure for simulator`() {
        // when
        IOSBuildProductsExtractor(
            target,
            context = Context.CLI,
            deviceType = IOSDeviceType.SIMULATOR,
        ).extract("driver-iPhoneSimulator")

        // then
        val configFile = target.resolve("maestro-driver-ios-config.xctestrun")
        val appDir = target.resolve("Debug-iphonesimulator/maestro-driver-ios.app")
        val runnerDir = target.resolve("Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app")

        assertTrue(Files.exists(configFile), "$configFile does not exist")
        assertTrue(Files.isDirectory(appDir), "$appDir is not a directory")
        assertTrue(Files.isDirectory(runnerDir), "$runnerDir is not a directory")
    }

    @AfterEach
    fun tearDown() {
        target.toFile().deleteRecursively()
    }
}