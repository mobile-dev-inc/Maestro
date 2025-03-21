package xcuitest.installer

import maestro.utils.HttpClient
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import org.apache.commons.io.FileUtils
import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class LocalXCTestInstaller(
    private val deviceId: String,
    private val host: String = "[::1]",
    private val enableXCTestOutputFileLogging: Boolean,
    private val defaultPort: Int,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    private val httpClient: OkHttpClient = HttpClient.build(
        name = "XCUITestDriverStatusCheck",
        connectTimeout = 1.seconds,
        readTimeout = 100.seconds,
    ),
    override val preBuiltRunner: Boolean = false,
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
    private val tempDir = "${System.getenv("TMPDIR")}/$deviceId"

    private var xcTestProcess: Process? = null

    override fun uninstall(): Boolean {
        return metrics.measured("operation", mapOf("command" to "uninstall")) {
            // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
            //  Just uninstalling should suffice. It automatically kills the process.

            if (useXcodeTestRunner) {
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
            startXCTestRunner(deviceId, preBuiltRunner)
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
                .host("[::1]")
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

        logger.info("[Start] Writing xctest run file")
        val tempDir = File(tempDir).apply { mkdir() }
        val xctestRunFile = File("$tempDir/maestro-driver-ios-config.xctestrun")
        writeFileToDestination(XCTEST_RUN_PATH, xctestRunFile)
        logger.info("[Done] Writing xctest run file")

        logger.info("[Start] Writing maestro-driver-iosUITests-Runner app")
        val bundlePath = extractZipToApp("maestro-driver-iosUITests-Runner", UI_TEST_RUNNER_PATH)
        logger.info("[Done] Writing maestro-driver-iosUITests-Runner app")

        logger.info("[Start] Writing maestro-driver-ios app")
        extractZipToApp("maestro-driver-ios", UI_TEST_HOST_PATH)
        logger.info("[Done] Writing maestro-driver-ios app")

        if (preBuiltRunner) {
            logger.info("Installing pre built driver without xcodebuild")
            LocalSimulatorUtils.install(deviceId, bundlePath.toPath())
            LocalSimulatorUtils.launchUITestRunner(deviceId, defaultPort)
        } else {
            logger.info("Installing driver with xcodebuild")
            logger.info("[Start] Running XcUITest with `xcodebuild test-without-building`")
            xcTestProcess = XCRunnerCLIUtils.runXcTestWithoutBuild(
                deviceId = this.deviceId,
                xcTestRunFilePath = xctestRunFile.absolutePath,
                port = defaultPort,
                enableXCTestOutputFileLogging = enableXCTestOutputFileLogging,
            )
            logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
        }
    }

    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        FileUtils.cleanDirectory(File(tempDir))
        uninstall()
        LocalSimulatorUtils.terminate(deviceId = deviceId, bundleId = UI_TEST_RUNNER_APP_BUNDLE_ID)
        XCRunnerCLIUtils.uninstall(bundleId = UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId = deviceId)
        logger.info("[Done] Cleaning up the ui test runner files")
    }

    private fun extractZipToApp(appFileName: String, srcAppPath: String): File {
        val bundlePath = File("$tempDir/Debug-iphonesimulator").apply { mkdir() }
        val appZip = File("$tempDir/$appFileName.zip")

        writeFileToDestination(srcAppPath, appZip)
        ArchiverFactory.createArchiver(appZip).apply {
            extract(appZip, bundlePath)
        }

        return File(bundlePath.path + "/$appFileName.app")
    }

    private fun writeFileToDestination(srcPath: String, destFile: File) {
        LocalXCTestInstaller::class.java.getResourceAsStream(srcPath)?.let {
            val bufferedSink = destFile.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
    }

    companion object {
        private const val UI_TEST_RUNNER_PATH = "/maestro-driver-iosUITests-Runner.zip"
        private const val XCTEST_RUN_PATH = "/maestro-driver-ios-config.xctestrun"
        private const val UI_TEST_HOST_PATH = "/maestro-driver-ios.zip"
        private const val UI_TEST_RUNNER_APP_BUNDLE_ID =
            "dev.mobile.maestro-driver-iosUITests.xctrunner"

        private const val SERVER_LAUNCH_TIMEOUT_MS = 180000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
    }

}
