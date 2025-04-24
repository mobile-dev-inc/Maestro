package maestro.cli.driver

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import io.mockk.spyk
import maestro.cli.api.CliVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readText

class DriverBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `test if driver is built successfully and written in directory`() {
        // given
        val mockProcess = mockk<Process>(relaxed = true)
        val mockProcessBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        val sourceCodeRoot = System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.home")
        every { mockProcess.waitFor(120, TimeUnit.SECONDS) } returns true // Simulate successful execution
        every { mockProcess.exitValue() } returns 0
        every { mockProcessBuilderFactory.createProcess(any(), any(), any()) }  answers {
            val derivedDataPath = Files.createDirectories(
                Paths.get(sourceCodeRoot , ".maestro", "maestro-iphoneos-driver-build", "driver-iphoneos", "Build", "Products")
            )
            val debugIphoneDir = Files.createDirectories(Paths.get(derivedDataPath.pathString, "Debug-iphoneos"))
            // Simulate creating build products
            File(derivedDataPath.toFile(), "maestro-driver-ios-config.xctestrun").writeText("Fake Runner xctestrun file")
            File(debugIphoneDir.toFile(), "maestro-driver-iosUITests-Runner.app").writeText("Fake Runner App Content")
            File(debugIphoneDir.toFile(), "maestro-driver-ios.app").writeText("Fake iOS Driver App Content")
            println("Simulated build process: Build products created in derived data path.")

            mockProcess // Return the mocked process
        }

        // when
        val builder = DriverBuilder(mockProcessBuilderFactory)
        val buildProducts = builder.buildDriver(
            DriverBuildConfig(
                teamId = "25CQD4CKK3",
                derivedDataPath = "driver-iphoneos",
                sourceCodePath = "driver/ios",
                sourceCodeRoot = System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.home"),
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

        Paths.get(System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.home"), "maestro-iphoneos-driver-build").toFile().deleteRecursively()
    }

    @Test
    fun `should write error output to file inside _maestro on build failure`() {
        // given
        val sourceCodeRoot = System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.home")
        val driverBuildConfig = mockk<DriverBuildConfig>()
        val processBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        val driverBuilder = spyk(DriverBuilder(processBuilderFactory))
        val mockProcess = mockk<Process>(relaxed = true)
        val capturedFileSlot = slot<File>()

        every { driverBuildConfig.sourceCodePath } returns  "mock/source"
        every { driverBuildConfig.sourceCodeRoot } returns sourceCodeRoot
        every { driverBuildConfig.derivedDataPath } returns  "mock/source"
        every { driverBuildConfig.teamId } returns "mock-team-id"
        every { driverBuildConfig.architectures } returns "arm64"
        every { driverBuildConfig.destination } returns "generic/platform=ios"
        every { driverBuildConfig.cliVersion } returns CliVersion.parse("1.40.0")
        every { driverBuilder.getDriverSourceFromResources(any()) } returns tempDir
        every { mockProcess.exitValue() } returns 1
        every { mockProcess.waitFor(120, TimeUnit.SECONDS) } returns true
        every {
            processBuilderFactory.createProcess(commands = any(), workingDirectory = any(), outputFile = capture(capturedFileSlot))
        } answers {
            capturedFileSlot.captured.writeText("xcodebuild failed!")
            mockProcess
        }

        // when
        val error = assertThrows(RuntimeException::class.java) {
            driverBuilder.buildDriver(driverBuildConfig)
        }

        // then
        assertThat(error.message).contains("Failed to build iOS driver for connected iOS device")
        // Verify that the error log has been written inside the `.maestro` directory
        val maestroDir = Paths.get(sourceCodeRoot, ".maestro")
        val errorLog = maestroDir.resolve("maestro-iphoneos-driver-build").resolve("output.log")

        // Verify file exists and contains error output
        assertTrue(Files.exists(errorLog), "Expected an error log file to be written.")
        assertTrue(errorLog.readText().contains("xcodebuild failed!"), "Log should contain build failure message.")

        Paths.get(System.getenv("GITHUB_WORKSPACE") ?: System.getProperty("user.home"), "maestro-iphoneos-driver-build").toFile().deleteRecursively()
    }
}