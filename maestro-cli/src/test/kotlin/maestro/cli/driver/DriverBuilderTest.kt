package maestro.cli.driver

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import io.mockk.spyk
import maestro.cli.api.CliVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

class DriverBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `test if driver is built successfully and written in directory`() {
        // given
        val builder = DriverBuilder()

        // when
        val buildProducts = builder.buildDriver(
            DriverBuildConfig(
                teamId = "25CQD4CKK3",
                derivedDataPath = "driver-iphoneos",
                sourceCodePath = "driver/ios",
                cliVersion = CliVersion(1, 40, 0)
            )
        )
        val xctestRunFile = buildProducts.toFile().walk().firstOrNull { it.extension == "xctestrun" }
        val appDir = buildProducts.resolve("Debug-iphoneos/maestro-driver-ios.app")
        val runnerDir = buildProducts.resolve("Debug-iphoneos/maestro-driver-iosUITests-Runner.app")


        // then
        assertThat(xctestRunFile?.exists()).isTrue()
        assertThat(appDir.exists()).isTrue()
        assertThat(runnerDir.exists()).isTrue()
    }

    @Test
    fun `should write error output to file inside _maestro on build failure`() {
        // given
        val driverBuildConfig = mockk<DriverBuildConfig>()
        val processBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        every { driverBuildConfig.sourceCodePath } returns  "mock/source"
        every { driverBuildConfig.derivedDataPath } returns  "mock/source"
        every { driverBuildConfig.teamId } returns "mock-team-id"
        every { driverBuildConfig.architectures } returns "arm64"
        every { driverBuildConfig.destination } returns "generic/platform=ios"
        every { driverBuildConfig.cliVersion } returns CliVersion.parse("1.40.0")

        val driverBuilder = spyk(DriverBuilder())
        every { driverBuilder.getDriverSourceFromResources(any()) } returns tempDir

        val mockErrorLog = tempDir.resolve("mock-output.log").apply {
            Files.write(this, "xcodebuild failed!".toByteArray())
        }
        val mockProcess = mockk<Process> {
            every { waitFor(120, TimeUnit.SECONDS) } returns true // Simulate normal timeout handling
            every { exitValue() } returns 1 // Simulate process failure
        }
        every {
            processBuilderFactory.createProcess(commands = any(), workingDirectory = any())
                .redirectOutput(mockErrorLog.toFile())
                .redirectError(mockErrorLog.toFile())
                .start()
        } returns mockProcess



        val error = assertThrows(RuntimeException::class.java) {
            driverBuilder.buildDriver(driverBuildConfig)
        }

        // Assert
        assertThat(error.message).contains("Failed to build driver, output log on")

        // Verify that the error log has been written inside the `.maestro` directory
        val maestroDir = Paths.get(System.getProperty("user.home"), ".maestro")
        val errorLog = maestroDir.resolve("maestro-iphoneos-driver-build").resolve("output.log")

        // Verify file exists and contains error output
        assertTrue(Files.exists(errorLog), "Expected an error log file to be written.")
        assertTrue(errorLog.readText().contains("maestro-driver-ios.xcodeproj' does not exist"), "Log should contain build failure message.")
    }
}