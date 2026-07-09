package xcuitest.installer

import device.IOSDevice
import device.IOSDeviceResourceOwner
import maestro.utils.HttpClient
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.TempFileHandler
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import util.IProxyHelper
import util.IOSDeviceType
import util.LocalIOSDeviceController
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
    private val tempFileHandler: TempFileHandler = TempFileHandler(),
    private val logsDir: File,
) : XCTestInstaller {

    private val logger = LoggerFactory.getLogger(LocalXCTestInstaller::class.java)
    private val metrics = metricsProvider.withPrefix("xcuitest.installer").withTags(mapOf("kind" to "local", "deviceId" to deviceId, "host" to host))

    /**
     * If true, allow for using a xctest runner started from Xcode.
     *
     * When this flag is set, maestro will not install, run, stop or remove the xctest runner.
     * Make sure to launch the xctest runner from Xcode whenever maestro needs it.
     */
    private var useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()
    private val tempDir = tempFileHandler.createTempDirectory(deviceId)
    private val localSimulatorUtils = LocalSimulatorUtils(tempFileHandler)
    private var iosBuildProductsExtractor = IOSBuildProductsExtractor(
        target = tempDir.toPath(),
        context = iOSDriverConfig.context,
        deviceType = deviceType,
    )
    private var xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)
    private var iProxyHelperFactory: () -> IProxyHelper = { IProxyHelper() }

    private var xcTestProcess: Process? = null
    private var iProxyHelper: IProxyHelper? = null
    private var forwardSession: IProxyHelper.ForwardSession? = null
    private var closed = false

    internal constructor(
        deviceId: String,
        host: String = "127.0.0.1",
        deviceType: IOSDeviceType,
        defaultPort: Int,
        metricsProvider: Metrics = MetricsProvider.getInstance(),
        httpClient: OkHttpClient,
        reinstallDriver: Boolean = true,
        iOSDriverConfig: IOSDriverConfig,
        deviceController: IOSDevice,
        tempFileHandler: TempFileHandler = TempFileHandler(),
        logsDir: File,
        dependencies: Dependencies,
    ) : this(
        deviceId = deviceId,
        host = host,
        deviceType = deviceType,
        defaultPort = defaultPort,
        metricsProvider = metricsProvider,
        httpClient = httpClient,
        reinstallDriver = reinstallDriver,
        iOSDriverConfig = iOSDriverConfig,
        deviceController = deviceController,
        tempFileHandler = tempFileHandler,
        logsDir = logsDir,
    ) {
        useXcodeTestRunner = dependencies.useXcodeTestRunner
        iosBuildProductsExtractor = dependencies.iosBuildProductsExtractor
        xcRunnerCLIUtils = dependencies.xcRunnerCLIUtils
        iProxyHelperFactory = dependencies.iProxyHelperFactory
    }

    override fun uninstall(): Boolean {
        return metrics.measured("operation", mapOf("command" to "uninstall")) {
            // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
            //  Just uninstalling should suffice. It automatically kills the process.

            if (useXcodeTestRunner || !reinstallDriver) {
                logger.trace("Skipping XCTest Runner uninstall because it is externally managed or reinstallDriver is false")
                return@measured false
            }

            stopXCTestRunnerProcess()
            if (deviceType == IOSDeviceType.SIMULATOR) {
                val pid = xcRunnerCLIUtils.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
                if (pid != null) {
                    logger.trace("Killing XCTest Runner process with the `kill` command")
                    ProcessBuilder(listOf("kill", pid.toString()))
                        .start()
                        .waitFor()
                }
            }

            logger.trace("Uninstalling XCTest Runner from device $deviceId")
            true
        }
    }

    override fun start(): XCTestClient {
        check(!closed) { "Cannot start XCTest because this installer has already been closed" }
        return metrics.measured("operation", mapOf("command" to "start")) {
            logger.info("start()")
            try {
                ensureForwardSession()

                if (useXcodeTestRunner) {
                    logger.info("USE_XCODE_TEST_RUNNER is set. Will wait for XCTest runner to be started manually")
                    if (ensureOpen()) {
                        return@measured XCTestClient(host, defaultPort)
                    }
                    throw IllegalStateException("XCTest was not started manually")
                }

                logger.info("[Start] Install XCUITest runner on $deviceId")
                startXCTestRunner(deviceId, iOSDriverConfig.prebuiltRunner)
                logger.info("[Done] Install XCUITest runner on $deviceId")

                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
                    // A dead forwarder can never come back; fail with a specific error instead of
                    // polling until the generic startup timeout.
                    forwardSession?.let {
                        if (!it.isAlive) {
                            throw IOException("USB port forwarding to device $deviceId was lost: ${it.failureDescription()}")
                        }
                    }
                    runCatching {
                        if (isChannelAlive()) return@measured XCTestClient(host, defaultPort)
                    }
                    Thread.sleep(500)
                }

                throw IOSDriverTimeoutException("iOS driver not ready in time, consider increasing timeout by configuring MAESTRO_DRIVER_STARTUP_TIMEOUT env variable")
            } catch (e: Exception) {
                cleanupFailedStart()
                throw e
            }
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
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            forwardSession?.let {
                if (!it.isAlive) {
                    throw IOException("USB port forwarding to device $deviceId was lost: ${it.failureDescription()}")
                }
            }
            if (isChannelAlive()) {
                logger.info("ensureOpen() finished, is channel alive?: true")
                return true
            }
            Thread.sleep(200)
        }
        logger.info("ensureOpen() finished, is channel alive?: false")
        return false
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
            logger.info("[Start] Running XcUITest with `xcodebuild test-without-building` with $defaultPort and config: $iOSDriverConfig")
            xcTestProcess = xcRunnerCLIUtils.runXcTestWithoutBuild(
                deviceId = this.deviceId,
                xcTestRunFilePath = buildProducts.xctestRunPath.absolutePath,
                port = defaultPort,
                snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                logsDir = logsDir,
            )
            logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
        }

    }

    private fun ensureForwardSession() {
        if (deviceType != IOSDeviceType.REAL || forwardSession?.isAlive == true) return

        closeForwardSession()
        val helper = iProxyHelperFactory().also { iProxyHelper = it }
        forwardSession = helper.forward(defaultPort, defaultPort, deviceId)
    }

    private fun cleanupFailedStart() {
        runCatching { close() }
            .onFailure { logger.warn("Failed to fully clean up after XCTest startup failure", it) }
    }

    private fun stopXCTestRunnerProcess() {
        val process = xcTestProcess ?: return
        if (!process.isAlive) {
            xcTestProcess = null
            return
        }

        logger.trace("XCTest Runner process started by us is alive, killing it")
        var interrupted = false
        try {
            runCatching { process.destroy() }
            var stopped = try {
                process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!stopped && process.isAlive) {
                runCatching { process.destroyForcibly() }
                stopped = try {
                    process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
            }
            if (!stopped && process.isAlive) {
                throw IllegalStateException("Could not terminate the xcodebuild process owned by this XCTest session")
            }
            xcTestProcess = null
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun closeForwardSession() {
        runCatching { forwardSession?.close() }
            .onFailure { logger.warn("Failed to close iproxy forward", it) }
        forwardSession = null
        runCatching { iProxyHelper?.close() }
            .onFailure { logger.warn("Failed to close iproxy helper", it) }
        iProxyHelper = null
    }

    private fun installPrebuiltRunner(deviceId: String, bundlePath: File) {
        logger.info("Installing prebuilt driver for $deviceId and type $deviceType")
        when (deviceType) {
            IOSDeviceType.REAL -> {
                LocalIOSDeviceController.install(deviceId, bundlePath.toPath())
                LocalIOSDeviceController.launchRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                    logsDir = logsDir,
                )
            }
            IOSDeviceType.SIMULATOR -> {
                localSimulatorUtils.install(deviceId, bundlePath.toPath())
                localSimulatorUtils.launchUITestRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                    logsDir = logsDir,
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        logger.info("[Start] Cleaning up the ui test runner files")
        try {
            if (!useXcodeTestRunner) {
                if (reinstallDriver) {
                    try {
                        uninstall()
                    } finally {
                        deviceController.close()
                    }
                } else {
                    // Keep the installed app, but never leave an xcodebuild process owned by
                    // this installer running after the session ends.
                    stopXCTestRunnerProcess()
                }
            }
        } finally {
            runCatching { (deviceController as? IOSDeviceResourceOwner)?.releaseResources() }
                .onFailure { logger.warn("Failed to release iOS device controller resources", it) }
            closeForwardSession()
            tempFileHandler.close()
            logger.info("[Done] Cleaning up the ui test runner files")
        }
    }

    data class IOSDriverConfig(
        val prebuiltRunner: Boolean,
        val sourceDirectory: String,
        val context: Context,
        val snapshotKeyHonorModalViews: Boolean?
    )

    internal data class Dependencies(
        val useXcodeTestRunner: Boolean,
        val iosBuildProductsExtractor: IOSBuildProductsExtractor,
        val xcRunnerCLIUtils: XCRunnerCLIUtils,
        val iProxyHelperFactory: () -> IProxyHelper,
    )

    companion object {
        const val UI_TEST_RUNNER_APP_BUNDLE_ID = "dev.mobile.maestro-driver-iosUITests.xctrunner"

        private const val SERVER_LAUNCH_TIMEOUT_MS = 120000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
        private const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L
    }

}
