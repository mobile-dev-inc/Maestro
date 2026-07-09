package maestro.cli.driver

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import io.mockk.spyk
import maestro.cli.api.CliVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
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
        val sourceCodeRoot = tempDir.resolve("home").also(Files::createDirectories).toString()
        every { mockProcess.waitFor(120, TimeUnit.SECONDS) } returns true // Simulate successful execution
        every { mockProcess.exitValue() } returns 0
        every { mockProcessBuilderFactory.createProcess(any(), any(), any()) }  answers {
            val derivedDataPath = Files.createDirectories(
                Paths.get(sourceCodeRoot , ".maestro", "maestro-iphoneos-driver-build", "driver-iphoneos", "Build", "Products")
            )
            val debugIphoneDir = Files.createDirectories(Paths.get(derivedDataPath.pathString, "Debug-iphoneos"))
            // Simulate creating build products
            File(derivedDataPath.toFile(), "maestro-driver-ios_iphoneos-arm64.xctestrun")
                .writeText("Fake Runner xctestrun file")
            createAppBundle(debugIphoneDir, "maestro-driver-iosUITests-Runner")
            createAppBundle(debugIphoneDir, "maestro-driver-ios")
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
                sourceCodeRoot = sourceCodeRoot,
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
        val marker = Paths.get(sourceCodeRoot, ".maestro/maestro-iphoneos-driver-build/version.properties")
        assertThat(marker.exists()).isTrue()
        val markerProperties = Properties().apply { Files.newBufferedReader(marker).use(::load) }
        assertThat(markerProperties.getProperty(DriverBuilder.MARKER_TEAM_ID)).isEqualTo("25CQD4CKK3")
        assertThat(markerProperties.getProperty(DriverBuilder.MARKER_DESTINATION)).isEqualTo("generic/platform=iphoneos")
        assertThat(markerProperties.getProperty(DriverBuilder.MARKER_CODE_SIGN_IDENTITY))
            .isEqualTo(DriverBuilder.CODE_SIGN_IDENTITY)

    }

    @Test
    fun `should write error output to file inside _maestro on build failure`() {
        // given
        val sourceCodeRoot = tempDir.resolve("home").also(Files::createDirectories).toString()
        val sourceDir = tempDir.resolve("source").also(Files::createDirectories)
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
        every { driverBuilder.getDriverSourceFromResources(any()) } returns sourceDir
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
        assertFalse(
            maestroDir.resolve("maestro-iphoneos-driver-build/version.properties").exists(),
            "A failed build must not be marked as cacheable.",
        )
    }

    @Test
    fun `timed out build is terminated and is not marked as valid`() {
        val sourceCodeRoot = tempDir.resolve("timeout-home").also(Files::createDirectories).toString()
        val sourceDir = tempDir.resolve("timeout-source").also(Files::createDirectories)
        val processBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        val driverBuilder = spyk(DriverBuilder(processBuilderFactory))
        val process = mockk<Process>(relaxed = true)
        val outputFile = slot<File>()

        every { driverBuilder.getDriverSourceFromResources(any()) } returns sourceDir
        every { process.waitFor(120, TimeUnit.SECONDS) } returns false
        every { process.waitFor(5, TimeUnit.SECONDS) } returns false
        every { process.destroyForcibly() } returns process
        every { processBuilderFactory.createProcess(any(), any(), capture(outputFile)) } answers {
            outputFile.captured.writeText("still building")
            process
        }

        val error = assertThrows(RuntimeException::class.java) {
            driverBuilder.buildDriver(
                DriverBuildConfig(
                    teamId = "25CQD4CKK3",
                    derivedDataPath = "driver-iphoneos",
                    sourceCodeRoot = sourceCodeRoot,
                    cliVersion = CliVersion(1, 40, 0),
                )
            )
        }

        assertThat(error).hasMessageThat().contains("timed out after 120 seconds")
        verify(exactly = 1) { process.destroy() }
        verify(exactly = 1) { process.destroyForcibly() }
        assertFalse(
            Paths.get(sourceCodeRoot, ".maestro/maestro-iphoneos-driver-build/version.properties").exists(),
            "A timed-out build must not be marked as cacheable.",
        )

    }

    @Test
    fun `interrupted build is terminated and preserves the interrupt`() {
        val sourceCodeRoot = tempDir.resolve("interrupted-home").also(Files::createDirectories).toString()
        val sourceDir = tempDir.resolve("interrupted-source").also(Files::createDirectories)
        val processBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        val driverBuilder = spyk(DriverBuilder(processBuilderFactory))
        val process = mockk<Process>(relaxed = true)

        every { driverBuilder.getDriverSourceFromResources(any()) } returns sourceDir
        every { process.waitFor(120, TimeUnit.SECONDS) } throws InterruptedException("cancelled")
        every { process.waitFor(5, TimeUnit.SECONDS) } returns true
        every { process.isAlive } returns true
        every { processBuilderFactory.createProcess(any(), any(), any()) } returns process

        try {
            assertThrows(InterruptedException::class.java) {
                driverBuilder.buildDriver(
                    DriverBuildConfig(
                        teamId = "25CQD4CKK3",
                        derivedDataPath = "driver-iphoneos",
                        sourceCodeRoot = sourceCodeRoot,
                        cliVersion = CliVersion(1, 40, 0),
                    )
                )
            }

            assertTrue(Thread.currentThread().isInterrupted)
            verify(exactly = 1) { process.destroy() }
            verify(exactly = 0) { process.destroyForcibly() }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `successful process without required products is not marked as valid`() {
        val sourceCodeRoot = tempDir.resolve("partial-home").also(Files::createDirectories).toString()
        val sourceDir = tempDir.resolve("partial-source").also(Files::createDirectories)
        val processBuilderFactory = mockk<XcodeBuildProcessBuilderFactory>()
        val driverBuilder = spyk(DriverBuilder(processBuilderFactory))
        val process = mockk<Process>(relaxed = true)

        every { driverBuilder.getDriverSourceFromResources(any()) } returns sourceDir
        every { process.waitFor(120, TimeUnit.SECONDS) } returns true
        every { process.exitValue() } returns 0
        every { processBuilderFactory.createProcess(any(), any(), any()) } returns process

        val error = assertThrows(RuntimeException::class.java) {
            driverBuilder.buildDriver(
                DriverBuildConfig(
                    teamId = "25CQD4CKK3",
                    derivedDataPath = "driver-iphoneos",
                    sourceCodeRoot = sourceCodeRoot,
                    cliVersion = CliVersion(1, 40, 0),
                )
            )
        }

        assertThat(error).hasMessageThat().contains("without required products")
        assertFalse(
            Paths.get(sourceCodeRoot, ".maestro/maestro-iphoneos-driver-build/version.properties").exists(),
            "A partial build must not be marked as cacheable.",
        )
    }

    @Test
    fun `empty app bundle directories are incomplete build products`() {
        val products = tempDir.resolve("empty-products").also(Files::createDirectories)
        products.resolve("maestro-driver-ios_iphoneos-arm64.xctestrun")
            .toFile().writeText("xctestrun")
        Files.createDirectories(products.resolve("Debug-iphoneos/maestro-driver-iosUITests-Runner.app"))
        Files.createDirectories(products.resolve("Debug-iphoneos/maestro-driver-ios.app"))

        assertThat(DriverBuilder.missingBuildProducts(products, "Debug")).containsExactly(
            "a complete maestro-driver-iosUITests-Runner.app",
            "a complete maestro-driver-ios.app",
        )
    }

    private fun createAppBundle(parent: Path, name: String) {
        val bundle = parent.resolve("$name.app").also(Files::createDirectories)
        bundle.resolve("Info.plist").toFile().writeText("plist")
        bundle.resolve(name).toFile().writeText("executable")
    }
}
