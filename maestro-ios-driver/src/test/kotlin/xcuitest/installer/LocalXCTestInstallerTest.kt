package xcuitest.installer

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.utils.NoOpMetrics
import maestro.utils.TempFileHandler
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import util.IOSDeviceType
import util.IProxyHelper
import util.XCRunnerCLIUtils
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createFile

class LocalXCTestInstallerTest {

    @Test
    fun `failed startup closes the real device forwarder`(@TempDir tempDir: Path) {
        val fixture = Fixture(tempDir, statusClient { false })
        every { fixture.extractor.extract(any()) } throws IllegalStateException("broken build products")
        val installer = fixture.installer(reinstallDriver = true)

        val error = assertThrows<IllegalStateException> { installer.start() }

        assertThat(error).hasMessageThat().contains("broken build products")
        verify(exactly = 1) { fixture.forwardSession.close() }
        verify(exactly = 1) { fixture.iProxyHelper.close() }
        verify(exactly = 1) { fixture.deviceController.close() }
    }

    @Test
    fun `failed startup makes the fully closed installer terminal`(@TempDir tempDir: Path) {
        val fixture = Fixture(tempDir, statusClient { false })
        every { fixture.extractor.extract(any()) } throws IllegalStateException("broken build products")
        val installer = fixture.installer(reinstallDriver = true)

        assertThrows<IllegalStateException> { installer.start() }
        val retryError = assertThrows<IllegalStateException> { installer.start() }

        assertThat(retryError).hasMessageThat().contains("installer has already been closed")
        verify(exactly = 1) { fixture.iProxyHelper.forward(TEST_PORT, TEST_PORT, DEVICE_ID) }
        verify(exactly = 1) { fixture.deviceController.close() }
    }

    @Test
    fun `failure after runner launch removes owned runner process and forwarding`(@TempDir tempDir: Path) {
        val fixture = Fixture(tempDir, statusClient { false })
        val xcodebuild = mockk<Process>(relaxed = true)
        every { xcodebuild.isAlive } returns true
        every { xcodebuild.waitFor(5, TimeUnit.SECONDS) } returns true
        every { fixture.forwardSession.isAlive } returns false
        every { fixture.forwardSession.failureDescription() } returns "forwarder exited"
        every { fixture.xcRunnerCLIUtils.runXcTestWithoutBuild(any(), any(), any(), any(), any()) } returns xcodebuild
        val installer = fixture.installer(reinstallDriver = true)

        val error = assertThrows<IOException> { installer.start() }

        assertThat(error).hasMessageThat().contains("USB port forwarding")
        verify(exactly = 1) { xcodebuild.destroy() }
        verify(exactly = 1) { fixture.deviceController.close() }
        verify(exactly = 1) { fixture.forwardSession.close() }
        verify(exactly = 1) { fixture.iProxyHelper.close() }
    }

    @Test
    fun `reinstall false keeps the runner installed but stops owned processes and forwarding`(@TempDir tempDir: Path) {
        val statusChecks = AtomicInteger()
        val fixture = Fixture(tempDir, statusClient { statusChecks.incrementAndGet() > 1 })
        val xcodebuild = mockk<Process>(relaxed = true)
        every { xcodebuild.isAlive } returns true
        every { xcodebuild.waitFor(5, TimeUnit.SECONDS) } returns true
        every { fixture.xcRunnerCLIUtils.runXcTestWithoutBuild(any(), any(), any(), any(), any()) } returns xcodebuild
        val installer = fixture.installer(reinstallDriver = false)

        installer.start()
        installer.close()

        verify(exactly = 1) { xcodebuild.destroy() }
        verify(exactly = 1) { xcodebuild.waitFor(5, TimeUnit.SECONDS) }
        verify(exactly = 0) { fixture.deviceController.close() }
        verify(exactly = 1) { fixture.forwardSession.close() }
        verify(exactly = 1) { fixture.iProxyHelper.close() }
    }

    @Test
    fun `interrupted runner cleanup force kills the child and preserves interruption`(@TempDir tempDir: Path) {
        val statusChecks = AtomicInteger()
        val fixture = Fixture(tempDir, statusClient { statusChecks.incrementAndGet() > 1 })
        val xcodebuild = mockk<Process>(relaxed = true)
        val terminationWaits = AtomicInteger()
        every { xcodebuild.isAlive } returns true
        every { xcodebuild.waitFor(5, TimeUnit.SECONDS) } answers {
            if (terminationWaits.getAndIncrement() == 0) throw InterruptedException("cancelled")
            true
        }
        every { fixture.xcRunnerCLIUtils.runXcTestWithoutBuild(any(), any(), any(), any(), any()) } returns xcodebuild
        val installer = fixture.installer(reinstallDriver = false)

        try {
            installer.start()
            installer.close()

            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(exactly = 1) { xcodebuild.destroy() }
            verify(exactly = 1) { xcodebuild.destroyForcibly() }
            verify(exactly = 2) { xcodebuild.waitFor(5, TimeUnit.SECONDS) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `manual runner mode only waits for the runner and still owns forwarding`(@TempDir tempDir: Path) {
        val fixture = Fixture(tempDir, statusClient { true })
        val installer = fixture.installer(
            reinstallDriver = true,
            useXcodeTestRunner = true,
        )

        installer.start()
        installer.close()

        verify(exactly = 0) { fixture.extractor.extract(any()) }
        verify(exactly = 0) {
            fixture.xcRunnerCLIUtils.runXcTestWithoutBuild(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { fixture.deviceController.close() }
        verify(exactly = 1) { fixture.forwardSession.close() }
        verify(exactly = 1) { fixture.iProxyHelper.close() }
    }

    private class Fixture(
        private val tempDir: Path,
        private val httpClient: OkHttpClient,
    ) {
        val extractor = mockk<IOSBuildProductsExtractor>()
        val xcRunnerCLIUtils = mockk<XCRunnerCLIUtils>(relaxed = true)
        val iProxyHelper = mockk<IProxyHelper>(relaxed = true)
        val forwardSession = mockk<IProxyHelper.ForwardSession>(relaxed = true)
        val deviceController = mockk<IOSDevice>(relaxed = true)

        init {
            val xctestRun = tempDir.resolve("runner.xctestrun").createFile().toFile()
            val runnerApp = tempDir.resolve("Runner.app").toFile().also(File::mkdirs)
            every { extractor.extract(any()) } returns BuildProducts(xctestRun, runnerApp)
            every { forwardSession.isAlive } returns true
            every { iProxyHelper.forward(TEST_PORT, TEST_PORT, DEVICE_ID) } returns forwardSession
        }

        fun installer(
            reinstallDriver: Boolean,
            useXcodeTestRunner: Boolean = false,
        ): LocalXCTestInstaller = LocalXCTestInstaller(
            deviceId = DEVICE_ID,
            deviceType = IOSDeviceType.REAL,
            defaultPort = TEST_PORT,
            metricsProvider = NoOpMetrics(),
            httpClient = httpClient,
            reinstallDriver = reinstallDriver,
            iOSDriverConfig = LocalXCTestInstaller.IOSDriverConfig(
                prebuiltRunner = false,
                sourceDirectory = tempDir.toString(),
                context = Context.CLI,
                snapshotKeyHonorModalViews = null,
            ),
            deviceController = deviceController,
            tempFileHandler = TempFileHandler(),
            logsDir = tempDir.toFile(),
            dependencies = LocalXCTestInstaller.Dependencies(
                useXcodeTestRunner = useXcodeTestRunner,
                iosBuildProductsExtractor = extractor,
                xcRunnerCLIUtils = xcRunnerCLIUtils,
                iProxyHelperFactory = { iProxyHelper },
            ),
        )
    }

    companion object {
        private const val DEVICE_ID = "00008110-TEST-DEVICE"
        private const val TEST_PORT = 22087

        private fun statusClient(isAlive: () -> Boolean): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (!isAlive()) throw IOException("driver is not ready")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            }
            .build()
    }
}
