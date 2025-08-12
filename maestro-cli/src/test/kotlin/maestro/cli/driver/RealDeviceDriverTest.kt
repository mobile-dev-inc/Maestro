package maestro.cli.driver

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import maestro.cli.api.CliVersion
import maestro.cli.util.EnvUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.pathString
import kotlin.io.path.writeText


class RealDeviceDriverTest {

    @TempDir
    lateinit var tempDir: Path // Temporary directory for test isolation


    @Test
    fun `should update driver when version is outdated`() {
        // Set up a test version.properties file with an outdated version
        // Mock CLI_VERSION in EnvUtils to return "1.2.0"
        mockkObject(EnvUtils)
        val driverBuilder = mockk<DriverBuilder>()
        every { driverBuilder.buildDriver(any()) } returns tempDir.resolve("Build/Products")
        every { EnvUtils.getCLIVersion() } returns CliVersion.parse("1.2.0")
        every { EnvUtils.CLI_VERSION } returns CliVersion.parse("1.2.0")
        val driverDirectory = Files.createDirectories(Paths.get(tempDir.pathString + "/maestro-iphoneos-driver-build"))
        val propertiesFile = driverDirectory.resolve("version.properties")
        val teamId = "dummy-team"
        val destination = "destination"


        Files.newBufferedWriter(propertiesFile).use { writer ->
            val props = Properties()
            props.setProperty("version", "1.1.0") // Outdated version
            props.store(writer, null)
        }

        // Call RealIOSDeviceDriver's validateAndUpdateDriver
        RealIOSDeviceDriver(teamId = teamId, destination = destination, driverBuilder)
            .validateAndUpdateDriver(driverRootDirectory = tempDir)

        // Verify that the driver was built due to outdated version
        verify(exactly = 1) { driverBuilder.buildDriver(any()) } // Assert buildDriver was called
        driverDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `should not update driver when version is up to date`() {
        // Set up a test version.properties file with an outdated version
        // Mock CLI_VERSION in EnvUtils to return "1.2.0"
        mockkObject(EnvUtils)
        val driverBuilder = mockk<DriverBuilder>()
        every { driverBuilder.buildDriver(any()) } returns tempDir.resolve("Build/Products")
        every { EnvUtils.getCLIVersion() } returns CliVersion.parse("1.3.0")
        every { EnvUtils.CLI_VERSION } returns CliVersion.parse("1.3.0")
        val driverDirectory = Files.createDirectories(Paths.get(tempDir.pathString + "/maestro-iphoneos-driver-build"))
        val productDirectory = driverDirectory.resolve("driver-iphoneos").resolve("Build").resolve("Products").createDirectories()
        productDirectory.resolve("maestro-driver-ios-config.xctestrun").createFile()
            .apply {
                writeText("Fake Runner xctestrun file")
            }
        val propertiesFile = driverDirectory.resolve("version.properties")
        val teamId = "dummy-team"
        val destination = "destination"


        Files.newBufferedWriter(propertiesFile).use { writer ->
            val props = Properties()
            props.setProperty("version", "1.3.0") // Outdated version
            props.store(writer, null)
        }

        // Call RealIOSDeviceDriver's validateAndUpdateDriver
        RealIOSDeviceDriver(teamId = teamId, destination = destination, driverBuilder)
            .validateAndUpdateDriver(driverRootDirectory = tempDir)

        // Verify that the driver was built due to outdated version
        verify(exactly = 0) { driverBuilder.buildDriver(any()) } // Assert buildDriver was called

        driverDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `should update driver when version file is missing`() {
        // Set up a test version.properties file with an outdated version
        // Mock CLI_VERSION in EnvUtils to return "1.2.0"
        mockkObject(EnvUtils)
        every { EnvUtils.getCLIVersion() } returns CliVersion.parse("1.3.0")
        every { EnvUtils.CLI_VERSION } returns CliVersion.parse("1.3.0")

        val driverBuilder = mockk<DriverBuilder>()
        every { driverBuilder.buildDriver(any()) } returns tempDir.resolve("Build/Products")

        val teamId = "dummy-team"
        val destination = "destination"

        // Call RealIOSDeviceDriver's validateAndUpdateDriver
        RealIOSDeviceDriver(teamId = teamId, destination = destination, driverBuilder)
            .validateAndUpdateDriver(driverRootDirectory = tempDir)

        // Verify that the driver was built due to outdated version
        verify(exactly = 1) { driverBuilder.buildDriver(any()) } // Assert buildDriver was called
    }

    @AfterEach
    fun cleanup() {
        clearAllMocks()
    }
}