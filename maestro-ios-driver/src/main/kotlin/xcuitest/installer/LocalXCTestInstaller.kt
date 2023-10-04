package xcuitest.installer

import logger.Logger
import maestro.utils.MaestroTimer
import maestro.utils.SocketUtils
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import okio.source
import org.rauschig.jarchivelib.ArchiverFactory
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocalXCTestInstaller(
    private val logger: Logger,
    private val deviceId: String,
    private val host: String = "[::1]",
    defaultPort: Int? = null,
) : XCTestInstaller {
    // Set this flag to allow using a test runner started from Xcode
    // When this flag is set, maestro will not install, run, stop or remove the xctest runner.
    // Make sure to launch the test runner from Xcode whenever maestro needs it.
    private val useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()
    private val tempDir = "${System.getenv("TMPDIR")}/$deviceId"

    private var xcTestProcess: Process? = null

    private val port = defaultPort ?: SocketUtils.nextFreePort(9800, 9900)

    override fun uninstall() {
        if (useXcodeTestRunner) {
            return
        }

        stop()

        logger.info("[Start] Uninstall XCUITest runner")
        XCRunnerCLIUtils.uninstall(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
        logger.info("[Done] Uninstall XCUITest runner")
    }

    private fun stop() {
        logger.info("[Start] Stop XCUITest runner")
        if (xcTestProcess?.isAlive == true) {
            xcTestProcess?.destroy()
        }

        val pid = XCRunnerCLIUtils.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
        if (pid != null) {
            ProcessBuilder(listOf("kill", pid.toString()))
                .start()
                .waitFor()
        }
        logger.info("[Done] Stop XCUITest runner")
    }

    override fun start(): XCTestClient? {
        if (useXcodeTestRunner) {
            repeat(20) {
                if (ensureOpen()) {
                    return XCTestClient(host, port)
                }
                logger.info("==> Start XCTest runner to continue flow")
                Thread.sleep(500)
            }
            throw IllegalStateException("XCTest was not started manually")
        }

        stop()

        repeat(3) { i ->
            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner()
            logger.info("[Done] Install XCUITest runner on $deviceId")

            logger.info("[Start] Ensure XCUITest runner is running on $deviceId")
            if (ensureOpen()) {
                logger.info("[Done] Ensure XCUITest runner is running on $deviceId")
                return XCTestClient(host, port)
            } else {
                logger.info("[Failed] Ensure XCUITest runner is running on $deviceId")
                logger.info("[Retry] Retrying setup() ${i}th time")
            }
        }
        return null
    }

    override fun isChannelAlive(): Boolean {
        return XCRunnerCLIUtils.isAppAlive(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId) &&
            xcTestDriverStatusCheck().use { it.isSuccessful }
    }

    private fun ensureOpen(): Boolean {
        return MaestroTimer.retryUntilTrue(10_000, 200) {
            try {
                XCRunnerCLIUtils.isAppAlive(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId) &&
                    xcTestDriverStatusCheck().use { it.isSuccessful }
            } catch (ignore: IOException) {
                false
            }
        }
    }

    private fun xcTestDriverStatusCheck(): Response {
        fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
            return HttpUrl.Builder()
                .scheme("http")
                .host("[::1]")
                .addPathSegment(pathSegment)
                .port(port)
        }

        val url = xctestAPIBuilder("status")
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(40, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS)
            .build()
        return okHttpClient.newCall(request).execute()
    }

    private fun startXCTestRunner() {
        val processOutput = ProcessBuilder(listOf("xcrun", "simctl", "spawn", deviceId, "launchctl", "list"))
            .start()
            .inputStream.source().buffer().readUtf8()
            .trim()

        logger.info("[Start] Writing xctest run file")
        val tempDir = File(tempDir).apply { mkdir() }
        val xctestRunFile = File("$tempDir/maestro-driver-ios-config.xctestrun")
        writeFileToDestination(XCTEST_RUN_PATH, xctestRunFile)
        logger.info("[Done] Writing xctest run file")

        if (processOutput.contains(UI_TEST_RUNNER_APP_BUNDLE_ID)) {
            logger.info("UI Test runner already running, stopping it")
            stop()
        } else {
            logger.info("Not able to find ui test runner app running, going to install now")

            logger.info("[Start] Writing maestro-driver-iosUITests-Runner app")
            extractZipToApp("maestro-driver-iosUITests-Runner", UI_TEST_RUNNER_PATH)
            logger.info("[Done] Writing maestro-driver-iosUITests-Runner app")

            logger.info("[Start] Writing maestro-driver-ios app")
            extractZipToApp("maestro-driver-ios", UI_TEST_HOST_PATH)
            logger.info("[Done] Writing maestro-driver-ios app")
        }

        logger.info("[Start] Running XcUITest with xcode build command")
        xcTestProcess = XCRunnerCLIUtils.runXcTestWithoutBuild(
            deviceId,
            xctestRunFile.absolutePath,
            port
        )
        logger.info("[Done] Running XcUITest with xcode build command")
    }

    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        File(tempDir).deleteRecursively()
        uninstall()
        logger.info("[Done] Cleaning up the ui test runner files")
    }

    private fun extractZipToApp(appFileName: String, srcAppPath: String) {
        val appFile = File("$tempDir/Debug-iphonesimulator").apply { mkdir() }
        val appZip = File("$tempDir/$appFileName.zip")

        writeFileToDestination(srcAppPath, appZip)
        ArchiverFactory.createArchiver(appZip).apply {
            extract(appZip, appFile)
        }
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
        private const val UI_TEST_RUNNER_APP_BUNDLE_ID = "dev.mobile.maestro-driver-iosUITests.xctrunner"
        private const val SPRINGBOARD_BUNDLE_ID = "com.apple.springboard"
    }
}
