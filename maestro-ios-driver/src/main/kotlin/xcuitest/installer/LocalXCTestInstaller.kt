package xcuitest.installer

import device.IOSDevice
import maestro.utils.HttpClient
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import util.LocalIOSDeviceController
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds

class LocalXCTestInstaller(
    private val deviceId: String,
    private val host: String = "127.0.0.1",
    private val deviceType: IOSDeviceType,
    private val defaultPort: Int,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    private val httpClient: OkHttpClient = HttpClient.build(
        name = "XCUITestDriverStatusCheck",
        connectTimeout = 1.seconds,
        readTimeout = 100.seconds,
    ),
    val reinstallDriver: Boolean = true,
    private val iOSDriverConfig: IOSDriverConfig,
    private val deviceController: IOSDevice,
) : XCTestInstaller {

    private val logger = LoggerFactory.getLogger(LocalXCTestInstaller::class.java)
    private val metrics = metricsProvider.withPrefix("xcuitest.installer").withTags(mapOf("kind" to "local", "deviceId" to deviceId, "host" to host))

    /**
     * If true, allow for using a xctest runner started from Xcode.
     *
     * When this flag is set, maestro will not install, run, stop or remove the xctest runner.
     * Make sure to launch the xctest runner from Xcode whenever maestro needs it.
     */
    private val useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()
    private val tempDir = Files.createTempDirectory(deviceId)
    private val iosBuildProductsExtractor = IOSBuildProductsExtractor(
        target = tempDir,
        context = iOSDriverConfig.context,
        deviceType = deviceType,
    )

    private var xcTestProcess: Process? = null

    override fun uninstall(): Boolean {
        return metrics.measured("operation", mapOf("command" to "uninstall")) {
            // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
            //  Just uninstalling should suffice. It automatically kills the process.

            if (useXcodeTestRunner || !reinstallDriver) {
                logger.trace("Skipping uninstalling XCTest Runner as USE_XCODE_TEST_RUNNER is set")
                return@measured false
            }

            if (!isChannelAlive()) return@measured false

            fun killXCTestRunnerProcess() {
                logger.trace("Will attempt to stop all alive XCTest Runner processes before uninstalling")

                if (xcTestProcess?.isAlive == true) {
                    logger.trace("XCTest Runner process started by us is alive, killing it")
                    xcTestProcess?.destroy()
                }
                xcTestProcess = null

                val pid = XCRunnerCLIUtils.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
                if (pid != null) {
                    logger.trace("Killing XCTest Runner process with the `kill` command")
                    ProcessBuilder(listOf("kill", pid.toString()))
                        .start()
                        .waitFor()
                }

                logger.trace("All XCTest Runner processes were stopped")
            }

            killXCTestRunnerProcess()

            logger.trace("Uninstalling XCTest Runner from device $deviceId")
            true
        }
    }

    override fun start(): XCTestClient {
        return metrics.measured("operation", mapOf("command" to "start")) {
            logger.info("start()")

            if (useXcodeTestRunner) {
                logger.info("USE_XCODE_TEST_RUNNER is set. Will wait for XCTest runner to be started manually")

                repeat(20) {
                    if (ensureOpen()) {
                        return@measured XCTestClient(host, defaultPort)
                    }
                    logger.info("==> Start XCTest runner to continue flow")
                    Thread.sleep(500)
                }
                throw IllegalStateException("XCTest was not started manually")
            }


            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner(deviceId, iOSDriverConfig.prebuiltRunner)
            logger.info("[Done] Install XCUITest runner on $deviceId")

            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
                runCatching {
                    if (isChannelAlive()) return@measured XCTestClient(host, defaultPort)
                }
                Thread.sleep(500)
            }

            throw IOSDriverTimeoutException("iOS driver not ready in time, consider increasing timeout by configuring MAESTRO_DRIVER_STARTUP_TIMEOUT env variable")
        }
    }

    class IOSDriverTimeoutException(message: String): RuntimeException(message)

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    override fun isChannelAlive(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isChannelAlive")) {
        return@measured xcTestDriverStatusCheck()
        }
    }

    private fun ensureOpen(): Boolean {
        val timeout = 120_000L
        logger.info("ensureOpen(): Will spend $timeout ms waiting for the channel to become alive")
        val result = MaestroTimer.retryUntilTrue(timeout, 200, onException = {
            logger.error("ensureOpen() failed with exception: $it")
        }) { isChannelAlive() }
        logger.info("ensureOpen() finished, is channel alive?: $result")
        return result
    }

    private fun xcTestDriverStatusCheck(): Boolean {
        logger.info("[Start] Perform XCUITest driver status check on $deviceId")
        fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
            return HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .addPathSegment(pathSegment)
                .port(defaultPort)
        }

        val url by lazy {
            xctestAPIBuilder("status")
                .build()
        }

        val request by lazy {  Request.Builder()
            .get()
            .url(url)
            .build()
        }

        val checkSuccessful = try {
            httpClient.newCall(request).execute().use {
                logger.info("[Done] Perform XCUITest driver status check on $deviceId")
                it.isSuccessful
            }
        } catch (ignore: IOException) {
            logger.info("[Failed] Perform XCUITest driver status check on $deviceId, exception: $ignore")
            false
        }

        return checkSuccessful
    }

    private fun startXCTestRunner(deviceId: String, preBuiltRunner: Boolean) {
        if (isChannelAlive()) {
            logger.info("UI Test runner already running, returning")
            return
        }

        val buildProducts = iosBuildProductsExtractor.extract(iOSDriverConfig.sourceDirectory)

        if (preBuiltRunner) {
            logger.info("Installing pre built driver without xcodebuild")
            installPrebuiltRunner(deviceId, buildProducts.uiRunnerPath)
        } else {
            logger.info("Installing driver with xcodebuild")
            logger.info("[Start] Running XcUITest with `xcodebuild test-without-building`")
            xcTestProcess = XCRunnerCLIUtils.runXcTestWithoutBuild(
                deviceId = this.deviceId,
                xcTestRunFilePath = buildProducts.xctestRunPath.absolutePath,
                port = defaultPort,
            )
            logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
        }
    }

    private fun installPrebuiltRunner(deviceId: String, bundlePath: File) {
        logger.info("Installing prebuilt driver for $deviceId and type $deviceType")
        when (deviceType) {
            IOSDeviceType.REAL -> {
                LocalIOSDeviceController.install(deviceId, bundlePath.toPath())
                LocalIOSDeviceController.launchRunner(deviceId, defaultPort)
            }
            IOSDeviceType.SIMULATOR -> {
                LocalSimulatorUtils.install(deviceId, bundlePath.toPath())
                LocalSimulatorUtils.launchUITestRunner(deviceId, defaultPort)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        tempDir.deleteRecursively()
        if(reinstallDriver) {
            uninstall()
            deviceController.close()
            logger.info("[Done] Cleaning up the ui test runner files")
        }
    }

    data class IOSDriverConfig(
        val prebuiltRunner: Boolean,
        val sourceDirectory: String,
        val context: Context
    )

    companion object {
        const val UI_TEST_RUNNER_APP_BUNDLE_ID = "dev.mobile.maestro-driver-iosUITests.xctrunner"

        private const val SERVER_LAUNCH_TIMEOUT_MS = 120000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
    }

}