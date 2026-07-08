/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.protobuf.ByteString
import maestro.*
import maestro.MaestroDriverStartupException.AndroidDriverTimeoutException
import maestro.MaestroDriverStartupException.AndroidInstrumentationSetupFailure
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.android.AndroidAppFiles
import maestro.android.AndroidDeviceConnection
import maestro.android.AndroidLaunchArguments.toAndroidLaunchArguments
import maestro.android.AndroidOperationFailedException
import maestro.android.chromedevtools.AndroidWebViewHierarchyClient
import maestro.android.crashes.LogcatCrashReport
import maestro.android.crashes.LogcatReader
import maestro.android.getActivityManagerLogs
import maestro.android.getAppCrashLogs
import maestro.android.orThrow
import maestro.android.orThrowOnFailure
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceArtifactFiles
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.utils.BlockingStreamObserver
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.ScreenshotUtils
import maestro.utils.StringUtils.toRegexSafe
import maestro_android.*
import net.dongliu.apk.parser.ApkFile
import okio.*
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.use

private val logger = LoggerFactory.getLogger(Maestro::class.java)

/**
 * Drives a single Android device. All transport — gRPC and dadb — is owned by
 * [connection]; this driver neither creates nor sees a raw `Dadb` or gRPC channel.
 */
class AndroidDriver(
    private val connection: AndroidDeviceConnection,
    private var emulatorName: String = "",
    private val reinstallDriver: Boolean = true,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
) : Driver {
    private var open = false
    private val hostPort: Int get() = connection.driverHostPort

    private val metrics = metricsProvider.withPrefix("maestro.driver").withTags(mapOf("platform" to "android", "emulatorName" to emulatorName))

    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    private val androidWebViewHierarchyClient = AndroidWebViewHierarchyClient(connection)

    private var instrumentationSession: AndroidDeviceConnection.InstrumentationSession? = null
    private var proxySet = false

    private var isLocationMocked = false
    private var chromeDevToolsEnabled = false

    override fun name(): String {
        return connection.name()
    }

    override fun open() {
        installMaestroApks()
        startInstrumentationSession(hostPort)

        try {
            awaitLaunch()
        } catch (ignored: InterruptedException) {
            instrumentationSession?.close()
            return
        }
    }

    private fun startInstrumentationSession(port: Int) {
        val startTime = System.currentTimeMillis()
        val apiLevel = getDeviceApiLevel()

        val instrumentationCommand = buildString {
            append("am instrument -w ")
            if (apiLevel >= 26) append("-m ")
            append("-e debug false ")
            append("-e class 'dev.mobile.maestro.MaestroDriverService#grpcServer' ")
            append("-e port $port ")
            append("dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner &\n")
        }

        open = true
        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            val session = connection.startInstrumentation(instrumentationCommand)
            instrumentationSession = session

            if (session.startedSuccessfully()) {
                return
            }

            session.close()
            Thread.sleep(100)
        }
        throw AndroidInstrumentationSetupFailure("Maestro instrumentation could not be initialized")
    }

    private fun getDeviceApiLevel(): Int =
        connection.shell("getprop ro.build.version.sdk").orThrow().trim().toIntOrNull()
            ?: throw AndroidOperationFailedException("Invalid API level from 'getprop ro.build.version.sdk'")


    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            if (connection.isDriverReachable(hostPort)) {
                return
            }
            Thread.sleep(100)
        }

        throw AndroidDriverTimeoutException("Maestro Android driver did not start up in time on emulator [ $emulatorName ] (driver port $hostPort)")
    }

    override fun close() {
        try {
            if (proxySet) {
                resetProxy()
            }
            if (isLocationMocked) {
                connection.execute("disableLocationUpdates") { it.disableLocationUpdates(emptyRequest { }) }.orThrow()
                isLocationMocked = false
            }

            LOGGER.info("[Start] Uninstall driver from device")
            if (reinstallDriver) {
                uninstallMaestroDriverApp()
            }
            if (reinstallDriver) {
                uninstallMaestroServerApp()
            }
            LOGGER.info("[Done] Uninstall driver from device")

            LOGGER.info("[Start] Close instrumentation session")
            instrumentationSession?.close()
            instrumentationSession = null
            LOGGER.info("[Done] Close instrumentation session")

            androidWebViewHierarchyClient.close()
        } finally {
            // Always shut the transport down — even if an earlier teardown step hit a transport death —
            // so the dadb socket / gRPC channel can't leak. The death (if any) still propagates after.
            LOGGER.info("[Start] Shutdown device connection")
            connection.close()
            LOGGER.info("[Done] Shutdown device connection")
        }
    }

    override fun deviceInfo(): DeviceInfo {
        val response = connection.execute("deviceInfo") { it.deviceInfo(deviceInfoRequest {}) }.orThrow()

        return DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels,
            widthGrid = response.widthPixels,
            heightGrid = response.heightPixels,
        )
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    ) {
        metrics.measured("operation", mapOf("command" to "launchApp", "appId" to appId)) {
            if(!open) // pick device flow, no open() invocation
                open()

            if (!isPackageInstalled(appId)) {
                throw MaestroException.UnableToLaunchApp("Package $appId is not installed")
            }

            val arguments = launchArguments.toAndroidLaunchArguments()
            connection.execute("launchApp") {
                it.launchApp(
                    launchAppRequest {
                        this.packageName = appId
                        this.arguments.addAll(arguments)
                    }
                )
            }.orThrow()
        }
    }

    override fun stopApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "stopApp", "appId" to appId)) {
            // Note: If the package does not exist, this call does *not* throw an exception
            shell("am force-stop $appId")
        }
    }

    override fun killApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "killApp", "appId" to appId)) {
            // Kill is the adb command needed to trigger System-initiated Process Death
            shell("am kill $appId")
        }
    }

    override fun clearAppState(appId: String) {
        metrics.measured("operation", mapOf("command" to "clearAppState", "appId" to appId)) {
            if (!isPackageInstalled(appId)) {
                return@measured
            }

            shell("pm clear $appId")
        }
    }

    override fun clearKeychain() {
        // No op
    }

    override fun tap(point: Point) {
        metrics.measured("operation", mapOf("command" to "tap")) {
            connection.execute("tap") {
                it.tap(
                    tapRequest {
                        x = point.x
                        y = point.y
                    }
                )
            }.orThrow()
        }
    }

    override fun longPress(point: Point) {
        metrics.measured("operation", mapOf("command" to "longPress")) {
            shell("input swipe ${point.x} ${point.y} ${point.x} ${point.y} 3000")
        }
    }

    override fun pressKey(code: KeyCode) {
        metrics.measured("operation", mapOf("command" to "pressKey")) {
            val intCode: Int = when (code) {
                KeyCode.ENTER -> 66
                KeyCode.BACKSPACE -> 67
                KeyCode.BACK -> 4
                KeyCode.VOLUME_UP -> 24
                KeyCode.VOLUME_DOWN -> 25
                KeyCode.HOME -> 3
                KeyCode.LOCK -> 276
                KeyCode.REMOTE_UP -> 19
                KeyCode.REMOTE_DOWN -> 20
                KeyCode.REMOTE_LEFT -> 21
                KeyCode.REMOTE_RIGHT -> 22
                KeyCode.REMOTE_CENTER -> 23
                KeyCode.REMOTE_PLAY_PAUSE -> 85
                KeyCode.REMOTE_STOP -> 86
                KeyCode.REMOTE_NEXT -> 87
                KeyCode.REMOTE_PREVIOUS -> 88
                KeyCode.REMOTE_REWIND -> 89
                KeyCode.REMOTE_FAST_FORWARD -> 90
                KeyCode.POWER -> 26
                KeyCode.ESCAPE -> 111
                KeyCode.TAB -> 62
                KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> 280
                KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> 281
                KeyCode.REMOTE_BUTTON_A -> 96
                KeyCode.REMOTE_BUTTON_B -> 97
                KeyCode.REMOTE_MENU -> 82
                KeyCode.TV_INPUT -> 178
                KeyCode.TV_INPUT_HDMI_1 -> 243
                KeyCode.TV_INPUT_HDMI_2 -> 244
                KeyCode.TV_INPUT_HDMI_3 -> 245
            }

            shell("input keyevent $intCode")
            Thread.sleep(300)
        }
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        return metrics.measured("operation", mapOf("command" to "contentDescriptor")) {
            val response = callViewHierarchy()

            val document = documentBuilderFactory
                .newDocumentBuilder()
                .parse(response.hierarchy.byteInputStream())

            val baseTree = mapHierarchy(document)

            val treeNode = androidWebViewHierarchyClient.augmentHierarchy(baseTree, chromeDevToolsEnabled)

            if (excludeKeyboardElements) {
                treeNode.excludeKeyboardElements() ?: treeNode
            } else {
                treeNode
            }
        }
    }

    private fun TreeNode.excludeKeyboardElements(): TreeNode? {
        val filtered = children.mapNotNull {
            it.excludeKeyboardElements()
        }.toList()

        val resourceId = attributes["resource-id"]
        if (resourceId != null && resourceId.startsWith("com.google.android.inputmethod.latin:id/")) {
            return null
        }
        return TreeNode(
            attributes = attributes,
            children = filtered,
            clickable = clickable,
            enabled = enabled,
            focused = focused,
            checked = checked,
            selected = selected
        )
    }

    private fun callViewHierarchy(): MaestroAndroid.ViewHierarchyResponse {
        return connection.execute("viewHierarchy") { it.viewHierarchy(viewHierarchyRequest {}) }.orThrow()
    }

    override fun scrollVertical() {
        metrics.measured("operation", mapOf("command" to "scrollVertical")) {
            swipe(SwipeDirection.UP, 400)
        }
    }

    override fun isKeyboardVisible(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isKeyboardVisible")) {
            val root = contentDescriptor().let {
                val deviceInfo = deviceInfo()
                val filtered = it.filterOutOfBounds(
                    width = deviceInfo.widthGrid,
                    height = deviceInfo.heightGrid
                )
                filtered ?: it
            }
            "com.google.android.inputmethod.latin:id" in jacksonObjectMapper().writeValueAsString(root)
        }
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithDirection", "direction" to swipeDirection.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            when (swipeDirection) {
                SwipeDirection.UP -> {
                    val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.DOWN -> {
                    val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.2f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.RIGHT -> {
                    val startX = (deviceInfo.widthGrid * 0.1f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }

                SwipeDirection.LEFT -> {
                    val startX = (deviceInfo.widthGrid * 0.9f).toInt()
                    val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                    val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                    val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                    directionalSwipe(
                        durationMs,
                        Point(startX, startY),
                        Point(endX, endY)
                    )
                }
            }
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithElementPoint", "direction" to direction.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            when (direction) {
                SwipeDirection.UP -> {
                    val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
                }

                SwipeDirection.DOWN -> {
                    val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
                }

                SwipeDirection.RIGHT -> {
                    val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
                }

                SwipeDirection.LEFT -> {
                    val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                    directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
                }
            }
        }
    }

    private fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
        metrics.measured("operation", mapOf("command" to "directionalSwipe", "durationMs" to durationMs.toString())) {
            shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
        }
    }

    override fun backPress() {
        metrics.measured("operation", mapOf("command" to "backPress")) {
            shell("input keyevent 4")
            Thread.sleep(300)
        }
    }

    override fun hideKeyboard() {
        metrics.measured("operation", mapOf("command" to "hideKeyboard")) {
            shell("input keyevent 4") // 'Back', which dismisses the keyboard before handing over to navigation
            Thread.sleep(300)
            waitForAppToSettle(null, null)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        metrics.measured("operation", mapOf("command" to "takeScreenshot", "compressed" to compressed.toString())) {
            val response = connection.execute("takeScreenshot") { it.screenshot(screenshotRequest {}) }.orThrow()
            out.buffer().use {
                it.write(response.bytes.toByteArray())
            }
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        return metrics.measured("operation", mapOf("command" to "startScreenRecording")) {

            val deviceScreenRecordingPath = "/sdcard/maestro-screenrecording.mp4"

            // Cloud worker devices bake an extended screenrecord entry point that lifts the
            // stock 180s time limit and pins encoder-safe dimensions (maestro-device's
            // ScreenrecordStep). Record through it when present. On stock devices only
            // API 34+ can disable the limit ("--time-limit 0"); below that, recordings
            // stop at the stock 180s cap.
            val extendedRecorder = EXTENDED_SCREENRECORD_PATH.takeIf {
                connection.shell("test -x $it").exitCode == 0
            }

            val future = CompletableFuture.runAsync({
                val recorderCommand = if (extendedRecorder != null) {
                    "$extendedRecorder --bit-rate '100000' $deviceScreenRecordingPath"
                } else {
                    val timeLimit = if (getDeviceApiLevel() >= 34) "--time-limit 0" else ""
                    "screenrecord $timeLimit --bit-rate '100000' $deviceScreenRecordingPath"
                }
                try {
                    shell(recorderCommand)
                } catch (e: AndroidOperationFailedException) {
                    // The screenrecord command itself failed (non-zero exit) — usually an emulator that can't
                    // record. Surface it as the op-failure type, not a bare IOException. A transport death is
                    // NOT caught here: it propagates as a device death.
                    throw AndroidOperationFailedException(
                        "Failed to capture screen recording on the device. Note that some Android emulators do not support " +
                            "screen recording. Try using a different Android emulator (eg. Pixel 5 / API 30): ${e.message}"
                    )
                }
            }, Executors.newSingleThreadExecutor())

            object : ScreenRecording {
                override fun close() {
                    // The extended entry point execs a patched copy named screenrecord-bin on
                    // images whose stock binary caps the time limit; SIGINT both names so the
                    // moov atom gets flushed regardless of which recorder ran.
                    connection.shell("killall -INT screenrecord screenrecord-bin") // Ignore exit code
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        // Unwrap so a transport death from the screenrecord task surfaces as the typed
                        // DeviceConnectionException, not an ExecutionException that bypasses death classification.
                        throw e.cause ?: e
                    }
                    Thread.sleep(3000)
                    connection.pull(out, deviceScreenRecordingPath).orThrowOnFailure()
                }
            }
        }
    }

    override fun inputText(text: String) {
        metrics.measured("operation", mapOf("command" to "inputText")) {
            if (Charsets.US_ASCII.newEncoder().canEncode(text)) {
                connection.execute("inputText") {
                    it.inputText(inputTextRequest {
                        this.text = text
                    })
                }.orThrow()
            } else {
                inputUnicodeText(text)
            }
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        metrics.measured("operation", mapOf("command" to "openLink", "appId" to appId, "autoVerify" to autoVerify.toString(), "browser" to browser.toString())) {
            if (browser) {
                openBrowser(link)
            } else {
                shell("am start -a android.intent.action.VIEW -d \"$link\"")
            }

            if (autoVerify) {
                autoVerifyApp(appId)
            }
        }
    }

    private fun autoVerifyApp(appId: String?) {
        if (appId != null) {
            autoVerifyWithAppName(appId)
        }
        autoVerifyChromeAgreement()
    }

    private fun autoVerifyWithAppName(appId: String) {
        val appNameResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(connection, appId)
            val appName = ApkFile(apkFile).apkMeta.name
            apkFile.delete()
            appName
        }
        if (appNameResult.isSuccess) {
            val appName = appNameResult.getOrThrow()
            waitUntilScreenIsStatic(3000)
            val appNameElement = filterByText(appName)
            if (appNameElement != null) {
                tap(appNameElement.bounds.center())
                filterById("android:id/button_once")?.let {
                    tap(it.bounds.center())
                }
            } else {
                val openWithAppElement = filterByText(".*$appName.*")
                if (openWithAppElement != null) {
                    filterById("android:id/button_once")?.let {
                        tap(it.bounds.center())
                    }
                }
            }
        }
    }

    private fun autoVerifyChromeAgreement() {
        filterById("com.android.chrome:id/terms_accept")?.let { tap(it.bounds.center()) }
        waitForAppToSettle(null, null)
        filterById("com.android.chrome:id/negative_button")?.let { tap(it.bounds.center()) }
    }

    private fun filterByText(textRegex: String): UiElement? {
        val textMatcher = Filters.textMatches(textRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(textMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun filterById(idRegex: String): UiElement? {
        val idMatcher = Filters.idMatches(idRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(idMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun openBrowser(link: String) {
        val installedPackages = installedPackages()
        when {
            installedPackages.contains("com.android.chrome") -> {
                shell("am start -a android.intent.action.VIEW -d \"$link\" com.android.chrome")
            }

            installedPackages.contains("org.mozilla.firefox") -> {
                shell("am start -a android.intent.action.VIEW -d \"$link\" org.mozilla.firefox")
            }

            else -> {
                shell("am start -a android.intent.action.VIEW -d \"$link\"")
            }
        }
    }

    private fun installedPackages() = shell("pm list packages").split("\n")
        .map { line: String -> line.split(":".toRegex()).toTypedArray() }
        .filter { parts: Array<String> -> parts.size == 2 }
        .map { parts: Array<String> -> parts[1] }

    override fun setLocation(latitude: Double, longitude: Double) {
        metrics.measured("operation", mapOf("command" to "setLocation")) {
            if (!isLocationMocked) {
                LOGGER.info("[Start] Setting up for mocking location $latitude, $longitude")
                shell("pm grant dev.mobile.maestro android.permission.ACCESS_FINE_LOCATION")
                shell("pm grant dev.mobile.maestro android.permission.ACCESS_COARSE_LOCATION")
                shell("appops set dev.mobile.maestro android:mock_location allow")
                connection.execute("enableMockLocationProviders") { it.enableMockLocationProviders(emptyRequest { }) }.orThrow()
                LOGGER.info("[Done] Setting up for mocking location $latitude, $longitude")

                isLocationMocked = true
            }

            connection.execute("setLocation") {
                it.setLocation(
                    setLocationRequest {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                )
            }.orThrow()
        }
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // Disable accelerometer based rotation before overriding orientation
        shell("settings put system accelerometer_rotation 0")

        when(orientation) {
            DeviceOrientation.PORTRAIT -> shell("settings put system user_rotation 0")
            DeviceOrientation.LANDSCAPE_LEFT -> shell("settings put system user_rotation 1")
            DeviceOrientation.UPSIDE_DOWN -> shell("settings put system user_rotation 2")
            DeviceOrientation.LANDSCAPE_RIGHT -> shell("settings put system user_rotation 3")
        }
    }

    override fun eraseText(charactersToErase: Int) {
        metrics.measured("operation", mapOf("command" to "eraseText", "charactersToErase" to charactersToErase.toString())) {
            connection.execute("eraseText") {
                it.eraseAllText(
                    eraseAllTextRequest {
                        this.charactersToErase = charactersToErase
                    }
                )
            }.orThrow()
        }
    }

    override fun setProxy(host: String, port: Int) {
        metrics.measured("operation", mapOf("command" to "setProxy")) {
            shell("""settings put global http_proxy "${host}:${port}"""")
            proxySet = true
        }
    }

    override fun resetProxy() {
        metrics.measured("operation", mapOf("command" to "resetProxy")) {
            shell("settings put global http_proxy :0")
        }
    }

    override fun isShutdown(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isShutdown")) {
            connection.isShutdown()
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        return metrics.measured("operation", mapOf("command" to "waitForAppToSettle", "appId" to appId, "timeoutMs" to timeoutMs.toString())) {
            if (appId != null) {
                waitForWindowToSettle(appId, initialHierarchy, timeoutMs)
            } else {
                ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
            }
        }
    }

    private fun waitForWindowToSettle(
        appId: String,
        initialHierarchy: ViewHierarchy?,
        timeoutMs: Int? = null
    ): ViewHierarchy {
        val endTime = System.currentTimeMillis() + WINDOW_UPDATE_TIMEOUT_MS
        var hierarchy: ViewHierarchy? = null
        do {
            val windowUpdating = connection.execute("isWindowUpdating") {
                it.isWindowUpdating(checkWindowUpdatingRequest {
                    this.appId = appId
                })
            }.orThrow().isWindowUpdating

            if (windowUpdating) {
                hierarchy = ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
            } else {
                Thread.sleep(50) // avoid busy-spinning the device server while the window is already settled
            }
        } while (System.currentTimeMillis() < endTime)

        return hierarchy ?: ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return metrics.measured("operation", mapOf("command" to "waitUntilScreenIsStatic", "timeoutMs" to timeoutMs.toString())) {
            ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this)
        }
    }

    override fun capabilities(): List<Capability> {
        return metrics.measured("operation", mapOf("command" to "capabilities")) {
            listOf(
                Capability.FAST_HIERARCHY
            )
        }
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        metrics.measured("operation", mapOf("command" to "setPermissions", "appId" to appId)) {
            val mutable = permissions.toMutableMap()
            mutable.remove("all")?.let { value ->
                setAllPermissions(appId, value)
            }

            mutable.forEach { permission ->
                translatePermissionName(permission.key).forEach { permissionName ->
                    setPermissionInternal(appId, permissionName, permission.value)
                }
            }
        }
    }

    override fun addMedia(mediaFiles: List<File>) {
        metrics.measured("operation", mapOf("command" to "addMedia", "mediaFilesCount" to mediaFiles.size.toString())) {
            LOGGER.info("[Start] Adding media files")
            mediaFiles.forEach { addMediaToDevice(it) }
            LOGGER.info("[Done] Adding media files")
        }
    }

    override fun isAirplaneModeEnabled(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isAirplaneModeEnabled")) {
            when (val result = shell("cmd connectivity airplane-mode").trim()) {
                "No shell command implementation.", "" -> {
                    LOGGER.debug("Falling back to old airplane mode read method")
                    when (val fallbackResult = shell("settings get global airplane_mode_on").trim()) {
                        "0" -> false
                        "1" -> true
                        else -> throw IllegalStateException("Received invalid response from while trying to read airplane mode state: $fallbackResult")
                    }
                }

                "disabled" -> false
                "enabled" -> true
                else -> throw IllegalStateException("Received invalid response while trying to read airplane mode state: $result")
            }
        }
    }

    override fun setAirplaneMode(enabled: Boolean) {
        metrics.measured("operation", mapOf("command" to "setAirplaneMode", "enabled" to enabled.toString())) {
            // fallback to old way on API < 28
            if (getDeviceApiLevel() < 28) {
                val num = if (enabled) 1 else 0
                shell("settings put global airplane_mode_on $num")
                // We need to broadcast the change to really apply it
                broadcastAirplaneMode(enabled)
                return@measured
            }
            val value = if (enabled) "enable" else "disable"
            shell("cmd connectivity airplane-mode $value")
        }
    }

    private fun broadcastAirplaneMode(enabled: Boolean) {
        val command = "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        try {
            shell(command)
        } catch (e: AndroidOperationFailedException) {
            // Permission denial is an operation failure (non-zero exit); retry as root. A transport
            // death propagates instead of being mistaken for a permission issue.
            if (e.message?.contains("Security exception: Permission Denial:") == true) {
                try {
                    shell("su root $command")
                } catch (e: AndroidOperationFailedException) {
                    throw MaestroException.NoRootAccess("Failed to broadcast airplane mode change. Make sure to run an emulator with root access for API < 28")
                }
            }
        }
    }

    override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) {
        this.chromeDevToolsEnabled = enabled
    }

    override fun startDeviceLogCapture() {
        try {
            connection.shell("logcat -c")
        } catch (e: Exception) {
            LOGGER.warn("Failed to clear logcat buffer before capture: ${e.message}")
        }
    }

    override fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> {
        return try {
            val logcatOutput = connection.shell("logcat -v time -d").output
            val logFile = File(outputDir, DeviceArtifactFiles.LOGCAT)
            logFile.writeText(logcatOutput)
            listOf(
                CapturedDeviceArtifact(
                    type = CapturedDeviceArtifact.Type.DEVICE_LOG,
                    file = logFile,
                    source = "emulator"
                )
            )
        } catch (e: Exception) {
            LOGGER.warn("Failed to collect logcat output: ${e.message}")
            emptyList()
        }
    }

    override fun collectCrashArtifacts(
        appId: String?,
        sinceEpochMs: Long,
        outputDir: File
    ): List<CapturedDeviceArtifact> {
        if (appId == null) return emptyList()

        val artifacts = mutableListOf<CapturedDeviceArtifact>()

        try {
            val crash = connection.getAppCrashLogs(appId)
                ?.let {
                    LogcatReader.findCrashes(it).getLastCrash(
                        LogcatCrashReport.TimeAgo(System.currentTimeMillis() - sinceEpochMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    )
                }
            if (crash != null) {
                val crashFile = File(outputDir, DeviceArtifactFiles.CRASH_REPORT)
                crashFile.writeText(crash.stackTrace)
                artifacts += CapturedDeviceArtifact(
                    type = CapturedDeviceArtifact.Type.CRASH_REPORT,
                    file = crashFile,
                    friendlyMessage = crash.cause.ifEmpty { "App crashed (unknown cause)" }
                )
            }
        } catch (e: Exception) {
            LOGGER.warn("Failed to collect crash artifacts for $appId: ${e.message}")
        }

        try {
            val anr = connection.getActivityManagerLogs()
                ?.let {
                    LogcatReader.findANRs(it).anrs
                        .filter { a -> a.packageId == appId && a.date.time >= sinceEpochMs }
                        .maxByOrNull { a -> a.date }
                }
            if (anr != null) {
                val anrFile = File(outputDir, DeviceArtifactFiles.ANR_REPORT)
                anrFile.writeText(anr.rawLog)
                artifacts += CapturedDeviceArtifact(
                    type = CapturedDeviceArtifact.Type.ANR_REPORT,
                    file = anrFile,
                    friendlyMessage = anr.friendlyMessage
                )
            }
        } catch (e: Exception) {
            LOGGER.warn("Failed to collect ANR artifacts for $appId: ${e.message}")
        }

        return artifacts
    }

    fun setDeviceLocale(country: String, language: String): Int {
        return metrics.measured("operation", mapOf("command" to "setDeviceLocale", "country" to country, "language" to language)) {
            shell("pm grant dev.mobile.maestro android.permission.CHANGE_CONFIGURATION")
            val output =
                shell("am broadcast -a dev.mobile.maestro.locale -n dev.mobile.maestro/.receivers.LocaleSettingReceiver --es lang $language --es country $country")
            extractSetLocaleResult(output)
        }
    }

    private fun extractSetLocaleResult(result: String): Int {
        val regex = Regex("result=(-?\\d+)")
        val match = regex.find(result)
        return match?.groups?.get(1)?.value?.toIntOrNull() ?: -1
    }

    private fun addMediaToDevice(mediaFile: File) {
        val namedSource = NamedSource(
            mediaFile.name,
            mediaFile.source(),
            mediaFile.extension,
            mediaFile.path
        )
        val ext =
            MediaExt.values().firstOrNull { it.extName == namedSource.extension } ?: throw IllegalArgumentException(
                "Extension .${namedSource.extension} is not yet supported for add media"
            )

        connection.stream("addMedia") { asyncStub ->
            val responseObserver = BlockingStreamObserver<MaestroAndroid.AddMediaResponse>()
            val requestStream = asyncStub.addMedia(responseObserver)

            val buffer = Buffer()
            val source = namedSource.source
            while (source.read(buffer, CHUNK_SIZE) != -1L) {
                requestStream.onNext(
                    addMediaRequest {
                        this.payload = payload {
                            data = ByteString.copyFrom(buffer.readByteArray())
                        }
                        this.mediaName = namedSource.name
                        this.mediaExt = ext.extName
                    }
                )
                buffer.clear()
            }
            source.close()
            requestStream.onCompleted()
            responseObserver.awaitResult()
        }
    }

    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissionsResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(connection, appId)
            val permissions = ApkFile(apkFile).apkMeta.usesPermissions
            apkFile.delete()
            permissions
        }
        if (permissionsResult.isSuccess) {
            permissionsResult.getOrNull()?.let {
                it.forEach { permission ->
                    setPermissionInternal(appId, permission, permissionValue)
                }
            }
        }
    }

    private val appOpsPermissions = setOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE"
    )

    private fun setPermissionInternal(appId: String, permission: String, rawValue: String) {
        try {
            if (permission in appOpsPermissions) {
                setAppOp(appId, permission, rawValue)
            } else {
                shell("pm ${translatePermissionValue(rawValue)} $appId $permission")
            }
        } catch (exception: AndroidOperationFailedException) {
            // Operation failure only: a non-changeable permission (e.g. you can't grant/deny INTERNET) is
            // ignored. A transport death (DeviceConnectionException) is NOT caught here — it propagates as
            // infra instead of being swallowed and mistaken for "permission not changeable".
            if (exception.message?.contains("is not a changeable permission type") == false) {
                logger.debug("Failed to set permission $permission for app $appId: ${exception.message}")
            }
        }
    }

    private fun setAppOp(appId: String, op: String, rawValue: String) {
        // appops uses the bare operation name (e.g. MANAGE_EXTERNAL_STORAGE), not the full permission string
        val opName = op.removePrefix("android.permission.")

        val appOpsValue = when (rawValue) {
            "allow" -> "allow"
            "deny" -> "deny"
            else -> "default" // "unset" resets to system default
        }

        shell("appops set --uid $appId $opName $appOpsValue")
    }

    private fun translatePermissionName(name: String): List<String> {
        return when (name) {
            "bluetooth" -> listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
            )

            "calendar" -> listOf(
                "android.permission.WRITE_CALENDAR",
                "android.permission.READ_CALENDAR"
            )

            "camera" -> listOf("android.permission.CAMERA")

            "contacts" -> listOf(
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS"
            )

            "location" -> listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            )

            "medialibrary" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO"
            )

            "microphone" -> listOf(
                "android.permission.RECORD_AUDIO"
            )

            "notifications" -> listOf(
                "android.permission.POST_NOTIFICATIONS"
            )

            "phone" -> listOf(
                "android.permission.CALL_PHONE",
                "android.permission.ANSWER_PHONE_CALLS",
            )

            "sms" -> listOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS"
            )

            "storage" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            )

            else -> listOf(name.replace("[^A-Za-z0-9._]+".toRegex(), ""))
        }
    }

    private fun translatePermissionValue(value: String): String {
        return when (value) {
            "allow" -> "grant"
            "deny" -> "revoke"
            "unset" -> "revoke"
            else -> "revoke"
        }
    }

    private fun mapHierarchy(node: Node): TreeNode {
        val attributes = if (node is Element) {
            val attributesBuilder = mutableMapOf<String, String>()

            if (node.hasAttribute("text")) {
                val text = node.getAttribute("text")
                attributesBuilder["text"] = text
            }

            if (node.hasAttribute("content-desc")) {
                attributesBuilder["accessibilityText"] = node.getAttribute("content-desc")
            }

            if (node.hasAttribute("hintText")) {
                attributesBuilder["hintText"] = node.getAttribute("hintText")
            }

            if (node.hasAttribute("class") && node.getAttribute("class") == TOAST_CLASS_NAME) {
                attributesBuilder["ignoreBoundsFiltering"] = true.toString()
            } else {
                attributesBuilder["ignoreBoundsFiltering"] = false.toString()
            }

            if (node.hasAttribute("resource-id")) {
                attributesBuilder["resource-id"] = node.getAttribute("resource-id")
            }

            if (node.hasAttribute("clickable")) {
                attributesBuilder["clickable"] = node.getAttribute("clickable")
            }

            if (node.hasAttribute("bounds")) {
                attributesBuilder["bounds"] = node.getAttribute("bounds")
            }

            if (node.hasAttribute("enabled")) {
                attributesBuilder["enabled"] = node.getAttribute("enabled")
            }

            if (node.hasAttribute("focused")) {
                attributesBuilder["focused"] = node.getAttribute("focused")
            }

            if (node.hasAttribute("checked")) {
                attributesBuilder["checked"] = node.getAttribute("checked")
            }

            if (node.hasAttribute("scrollable")) {
                attributesBuilder["scrollable"] = node.getAttribute("scrollable")
            }

            if (node.hasAttribute("selected")) {
                attributesBuilder["selected"] = node.getAttribute("selected")
            }

            if (node.hasAttribute("class")) {
                attributesBuilder["class"] = node.getAttribute("class")
            }

            if (node.hasAttribute("important-for-accessibility")) {
                attributesBuilder["important-for-accessibility"] = node.getAttribute("important-for-accessibility")
            }

            if (node.hasAttribute("error")) {
                attributesBuilder["error"] = node.getAttribute("error")
            }

            attributesBuilder
        } else {
            emptyMap()
        }

        val children = mutableListOf<TreeNode>()
        val childNodes = node.childNodes
        (0 until childNodes.length).forEach { i ->
            children += mapHierarchy(childNodes.item(i))
        }

        return TreeNode(
            attributes = attributes.toMutableMap(),
            children = children,
            clickable = node.getBoolean("clickable"),
            enabled = node.getBoolean("enabled"),
            focused = node.getBoolean("focused"),
            checked = node.getBoolean("checked"),
            selected = node.getBoolean("selected"),
        )
    }

    private fun Node.getBoolean(name: String): Boolean? {
        return (this as? Element)
            ?.getAttribute(name)
            ?.let { it == "true" }
    }

    fun installMaestroDriverApp() {
        metrics.measured("operation", mapOf("command" to "installMaestroDriverApp")) {
            if (reinstallDriver) {
                uninstallMaestroDriverApp()
            } else if (isPackageInstalled("dev.mobile.maestro")) {
                return@measured
            }

            val maestroAppApk = File.createTempFile("maestro-app", ".apk")

            Maestro::class.java.getResourceAsStream("/maestro-app.apk")?.let {
                val bufferedSink = maestroAppApk.sink().buffer()
                bufferedSink.writeAll(it.source())
                bufferedSink.flush()
            }

            install(maestroAppApk)
            if (!isPackageInstalled("dev.mobile.maestro")) {
                throw IllegalStateException("dev.mobile.maestro was not installed")
            }
            maestroAppApk.delete()
        }
    }

    private fun installMaestroServerApp() {
        if (reinstallDriver) {
            uninstallMaestroServerApp()
        } else if (isPackageInstalled("dev.mobile.maestro.test")) {
            return
        }

        val maestroServerApk = File.createTempFile("maestro-server", ".apk")

        Maestro::class.java.getResourceAsStream("/maestro-server.apk")?.let {
            val bufferedSink = maestroServerApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }

        install(maestroServerApk)
        if (!isPackageInstalled("dev.mobile.maestro.test")) {
            throw IllegalStateException("dev.mobile.maestro.test was not installed")
        }
        maestroServerApk.delete()
    }

    private fun installMaestroApks() {
        installMaestroDriverApp()
        installMaestroServerApp()
    }

    fun uninstallMaestroDriverApp() {
        metrics.measured("operation", mapOf("command" to "uninstallMaestroDriverApp")) {
            bestEffortUninstall("dev.mobile.maestro")
        }
    }

    private fun uninstallMaestroServerApp() {
        bestEffortUninstall("dev.mobile.maestro.test")
    }

    /**
     * Best-effort uninstall: swallow both a rejected uninstall (AndroidOperationFailedException) and a
     * transport blip (a device-death IOException) — a subsequent install re-surfaces a real death.
     */
    private fun bestEffortUninstall(packageName: String) {
        try {
            if (isPackageInstalled(packageName)) {
                uninstall(packageName)
            }
        } catch (e: AndroidOperationFailedException) {
            // A rejected uninstall is an operation failure — best-effort, retry once. A transport death
            // (DeviceConnectionException) is NOT caught: it propagates as infra (fail-fast on a dead device).
            logger.warn("Failed to check or uninstall $packageName: ${e.message}")
            try {
                uninstall(packageName)
            } catch (e2: AndroidOperationFailedException) {
                logger.warn("Failed to uninstall $packageName: ${e2.message}")
            }
        }
    }

    private fun install(apkFile: File) {
        connection.install(apkFile).orThrowOnFailure()
    }

    private fun uninstall(packageName: String) {
        connection.uninstall(packageName).orThrowOnFailure()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        try {
            val output: String = shell("pm list packages --user 0 $packageName")
            return output.split("\n".toRegex())
                .map { line -> line.split(":".toRegex()) }
                .filter { parts -> parts.size == 2 }
                .map { parts -> parts[1] }
                .any { linePackageName -> linePackageName == packageName }
        } catch (e: AndroidOperationFailedException) {
            // Operation failure only (e.g. `pm list` non-zero) — log and rethrow. A transport death is NOT
            // caught here; it propagates as infra without being mislabeled "failed to check if installed".
            logger.warn("Failed to check if package $packageName is installed: ${e.message}")
            throw e
        }
    }

    // The connection owns the throw-on-failure logic (AdbShellResponse.orThrow); the driver just opts in.
    // A non-zero exit becomes an AndroidOperationFailedException (operation failure); a transport death is
    // already a Device*Exception from connection.shell and is never reclassified here.
    private fun shell(command: String): String = connection.shell(command).orThrow()

    private fun inputUnicodeText(text: String) {
        val originalIme = currentInputMethod().takeUnless { it.isBlank() || it == "null" }

        try {
            shell("ime enable $MAESTRO_IME_ID")
            shell("ime set $MAESTRO_IME_ID")
            waitForMaestroIme()

            chunkPreservingSurrogatePairs(text, MAX_UNICODE_INPUT_CHUNK_SIZE).forEach { chunk ->
                val encodedChunk = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(chunk.toByteArray(Charsets.UTF_8))
                val output = shell(
                    "am broadcast -a $MAESTRO_IME_COMMIT_ACTION -n $MAESTRO_IME_RECEIVER --es $MAESTRO_IME_EXTRA_TEXT '$encodedChunk'"
                )

                if (!output.contains("result=0")) {
                    throw IOException("Unicode input failed: $output")
                }
            }

            Thread.sleep(MAESTRO_IME_COMMIT_SETTLE_DELAY_MS)
        } finally {
            originalIme?.let { imeId ->
                runCatching {
                    shell("ime set $imeId")
                }.onFailure { error ->
                    logger.warn("Failed to restore original input method $imeId: ${error.message}")
                }
            }
        }
    }

    // Splits into chunks of at most [maxChunkSize] UTF-16 code units without ever severing a
    // surrogate pair across a boundary, so multi-code-unit characters (e.g. emoji) survive the
    // UTF-8 encoding of each chunk intact.
    private fun chunkPreservingSurrogatePairs(text: String, maxChunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChunkSize, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
                end--
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private fun currentInputMethod(): String {
        return shell("settings get secure default_input_method").trim()
    }

    private fun waitForMaestroIme() {
        val deadline = System.currentTimeMillis() + MAESTRO_IME_READY_TIMEOUT_MS
        var lastStatus = "Timed out waiting for Maestro IME"

        while (System.currentTimeMillis() < deadline) {
            val output = shell(
                "am broadcast -a $MAESTRO_IME_STATUS_ACTION -n $MAESTRO_IME_RECEIVER"
            )

            if (output.contains("result=0")) {
                return
            }

            lastStatus = output.lineSequence()
                .firstOrNull { it.contains("data=\"") }
                ?.substringAfter("data=\"")
                ?.substringBeforeLast("\"")
                ?: output.trim()

            Thread.sleep(MAESTRO_IME_SWITCH_DELAY_MS)
        }

        throw IOException(lastStatus)
    }

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    companion object {

        private const val SERVER_LAUNCH_TIMEOUT_MS = 15000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
        private const val WINDOW_UPDATE_TIMEOUT_MS = 750
        private const val MAESTRO_IME_ID = "dev.mobile.maestro/.input.MaestroInputMethodService"
        private const val MAESTRO_IME_RECEIVER = "dev.mobile.maestro/.receivers.UnicodeInputReceiver"
        private const val MAESTRO_IME_COMMIT_ACTION = "dev.mobile.maestro.ime.commitText"
        private const val MAESTRO_IME_STATUS_ACTION = "dev.mobile.maestro.ime.status"
        private const val MAESTRO_IME_EXTRA_TEXT = "textBase64"
        private const val MAESTRO_IME_SWITCH_DELAY_MS = 300L
        private const val MAESTRO_IME_READY_TIMEOUT_MS = 5_000L
        private const val MAESTRO_IME_COMMIT_SETTLE_DELAY_MS = 250L
        private const val MAX_UNICODE_INPUT_CHUNK_SIZE = 1000

        private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        private val LOGGER = LoggerFactory.getLogger(AndroidDriver::class.java)

        private const val TOAST_CLASS_NAME = "android.widget.Toast"
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
        private const val CHUNK_SIZE = 1024L * 1024L * 3L

        // Extended screenrecord entry point baked into cloud worker AVDs by
        // maestro-device's ScreenrecordStep. Three things are a contract with that
        // step and must change together or not at all: this path, the pass-through
        // arg shape, and the name of the process the entry point execs on patched
        // images (screenrecord-bin, which close() must SIGINT for the moov atom
        // to be flushed).
        private const val EXTENDED_SCREENRECORD_PATH = "/data/local/tmp/screenrecord"
    }
}
