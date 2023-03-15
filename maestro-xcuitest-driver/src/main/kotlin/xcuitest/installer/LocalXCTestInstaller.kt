package xcuitest.installer

import maestro.logger.Logger
import maestro.utils.MaestroTimer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import okio.source
import org.rauschig.jarchivelib.ArchiverFactory
import util.XCRunnerSimctl
import java.io.File
import java.net.ConnectException
import java.util.concurrent.TimeUnit

class LocalXCTestInstaller(
    private val logger: Logger,
    private val deviceId: String,
) : XCTestInstaller {
    // Set this flag to allow using a test runner started from Xcode
    // When this flag is set, maestro will not install, run, stop or remove the xctest runner.
    // Make sure to launch the test runner from Xcode whenever maestro needs it.
    private val useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()

    private var xcTestProcess: Process? = null

    override fun uninstall() {
        if (useXcodeTestRunner) {
            return
        }

        stop()

        logger.info("[Start] Uninstall XCUITest runner")
        XCRunnerSimctl.uninstall(UI_TEST_RUNNER_APP_BUNDLE_ID)
        logger.info("[Done] Uninstall XCUITest runner")
    }

    private fun stop() {
        if (xcTestProcess?.isAlive == true) {
            logger.info("[Start] Stop XCUITest runner")
            xcTestProcess?.destroy()
            logger.info("[Done] Stop XCUITest runner")
        }

        val pid = XCRunnerSimctl.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID)
        if (pid != null) {
            ProcessBuilder(listOf("kill", pid.toString()))
                .start()
                .waitFor()
        }
    }

    override fun start(): Boolean {
        if (useXcodeTestRunner) {
            return ensureOpen()
        }

        stop()

        repeat(3) { i ->
            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner()
            logger.info("[Done] Install XCUITest runner on $deviceId")

            logger.info("[Start] Ensure XCUITest runner is running on $deviceId")
            if (ensureOpen()) {
                logger.info("[Done] Ensure XCUITest runner is running on $deviceId")
                return true
            } else {
                logger.info("[Failed] Ensure XCUITest runner is running on $deviceId")
                logger.info("[Retry] Retrying setup() ${i}th time")
            }
        }
        return false
    }

    override fun isChannelAlive(): Boolean {
        return XCRunnerSimctl.isAppAlive(UI_TEST_RUNNER_APP_BUNDLE_ID) &&
            subTreeOfRunnerApp().use { it.isSuccessful }
    }

    private fun ensureOpen(): Boolean {
        XCRunnerSimctl.ensureAppAlive(UI_TEST_RUNNER_APP_BUNDLE_ID)
        return MaestroTimer.retryUntilTrue(10_000, 100) {
            try {
                subTreeOfRunnerApp().use { it.isSuccessful }
            } catch (ignore: ConnectException) {
                false
            }
        }
    }

    private fun subTreeOfRunnerApp(): Response {
        fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
            return HttpUrl.Builder()
                .scheme("http")
                .host("localhost")
                .addPathSegment(pathSegment)
                .port(22087)
        }
        val url = xctestAPIBuilder("subTree")
            .addQueryParameter("appId", UI_TEST_RUNNER_APP_BUNDLE_ID)
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return okHttpClient.newCall(request).execute()
    }

    private fun startXCTestRunner() {
        val processOutput = ProcessBuilder(listOf("xcrun", "simctl", "spawn", "booted", "launchctl", "list"))
            .start()
            .inputStream.source().buffer().readUtf8()
            .trim()

        if (!processOutput.contains(UI_TEST_RUNNER_APP_BUNDLE_ID)) {
            logger.info("Not able to find ui test runner app, going to install now")
            val tempDir = System.getenv("TMPDIR")
            val xctestRunFile = File("$tempDir/maestro-driver-ios-config.xctestrun")

            logger.info("[Start] Writing xctest run file")
            writeFileToDestination(XCTEST_RUN_PATH, xctestRunFile)
            logger.info("[Done] Writing xctest run file")

            logger.info("[Start] Writing maestro-driver-iosUITests-Runner app")
            extractZipToApp("maestro-driver-iosUITests-Runner", UI_TEST_RUNNER_PATH)
            logger.info("[Done] Writing maestro-driver-iosUITests-Runner app")

            logger.info("[Start] Writing maestro-driver-ios app")
            extractZipToApp("maestro-driver-ios", UI_TEST_HOST_PATH)
            logger.info("[Done] Writing maestro-driver-ios app")

            logger.info("[Start] Running XcUITest with xcode build command")
            xcTestProcess = XCRunnerSimctl.runXcTestWithoutBuild(
                deviceId,
                xctestRunFile.absolutePath
            )
            logger.info("[Done] Running XcUITest with xcode build command")
        } else {
            logger.info("UI test runner already installed and running")
        }
    }

    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        val xctestConfig = "${System.getenv("TMP_DIR")}/$XCTEST_RUN_PATH"
        val hostApp = "${System.getenv("TMPDIR")}/Debug-iphonesimulator/maestro-driver-ios.app"
        val uiTestRunnerApp = "${System.getenv("TMPDIR")}/Debug-iphonesimulator/maestro-driver-iosUITests-Runner.app"
        File(xctestConfig).delete()
        File(uiTestRunnerApp).deleteRecursively()
        File(hostApp).deleteRecursively()
        uninstall()
        logger.info("[Done] Cleaning up the ui test runner files")
    }

    private fun extractZipToApp(appFileName: String, srcAppPath: String) {
        val appFile = File("${System.getenv("TMPDIR")}/Debug-iphonesimulator").apply { mkdir() }
        val appZip = File("${System.getenv("TMPDIR")}/$appFileName.zip")

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
    }
}
