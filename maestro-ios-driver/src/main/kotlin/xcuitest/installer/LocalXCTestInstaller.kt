package xcuitest.installer

import maestro.utils.MaestroTimer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import org.apache.commons.io.FileUtils
import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocalXCTestInstaller(
    private val deviceId: String,
    private val host: String = "[::1]",
    private val enableXCTestOutputFileLogging: Boolean,
    private val defaultPort: Int,
) : XCTestInstaller {

    private val logger = LoggerFactory.getLogger(LocalXCTestInstaller::class.java)

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
        // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
        //  Just uninstalling should suffice. It automatically kills the process.

        if (useXcodeTestRunner) {
            logger.trace("Skipping uninstalling XCTest Runner as USE_XCODE_TEST_RUNNER is set")
            return false
        }

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
        XCRunnerCLIUtils.uninstall(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
        return true
    }

    override fun start(): XCTestClient? {
        if (useXcodeTestRunner) {
            logger.info("USE_XCODE_TEST_RUNNER is set. Will wait for XCTest runner to be started manually")

            repeat(20) {
                if (ensureOpen()) {
                    return XCTestClient(host, defaultPort)
                }
                logger.info("==> Start XCTest runner to continue flow")
                Thread.sleep(500)
            }
            throw IllegalStateException("XCTest was not started manually")
        }

        uninstall()

        repeat(3) { i ->
            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner()
            logger.info("[Done] Install XCUITest runner on $deviceId")

            logger.info("[Start] Ensure XCUITest runner is running on $deviceId")
            if (ensureOpen()) {
                logger.info("[Done] Ensure XCUITest runner is running on $deviceId")
                return XCTestClient(host, defaultPort)
            } else {
                uninstall()
                logger.info("[Failed] Ensure XCUITest runner is running on $deviceId")
                logger.info("[Retry] Retrying setup() ${i}th time")
            }
        }
        return null
    }

    override fun isChannelAlive(): Boolean {
        val appAlive = XCRunnerCLIUtils.isAppAlive(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
        return appAlive && xcTestDriverStatusCheck()
    }

    private fun ensureOpen(): Boolean {
        return MaestroTimer.retryUntilTrue(10_000, 200) { isChannelAlive() }
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

        val checkSuccessful = try {
            okHttpClient.newCall(request).execute().use {
                logger.info("[Done] Perform XCUITest driver status check on $deviceId")
                it.isSuccessful
            }
        } catch (ignore: IOException) {
            logger.info("[Failed] Perform XCUITest driver status check on $deviceId, exception: $ignore")
            false
        }

        return checkSuccessful
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
            uninstall()
        } else {
            logger.info("Not able to find ui test runner app running, going to install now")

            logger.info("[Start] Writing maestro-driver-iosUITests-Runner app")
            extractZipToApp("maestro-driver-iosUITests-Runner", UI_TEST_RUNNER_PATH)
            logger.info("[Done] Writing maestro-driver-iosUITests-Runner app")

            logger.info("[Start] Writing maestro-driver-ios app")
            extractZipToApp("maestro-driver-ios", UI_TEST_HOST_PATH)
            logger.info("[Done] Writing maestro-driver-ios app")
        }

        logger.info("[Start] Running XcUITest with `xcodebuild test-without-building`")
        xcTestProcess = XCRunnerCLIUtils.runXcTestWithoutBuild(
            deviceId = deviceId,
            xcTestRunFilePath = xctestRunFile.absolutePath,
            port = defaultPort,
            enableXCTestOutputFileLogging = enableXCTestOutputFileLogging,
        )
        logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
    }

    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        FileUtils.cleanDirectory(File(tempDir))
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
    }
}
