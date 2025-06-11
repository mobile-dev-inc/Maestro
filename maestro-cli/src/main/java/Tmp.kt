import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dadb.Dadb
import device.SimctlIOSDevice
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro.Point
import maestro.SwipeDirection
import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.cli.runner.TestRunner
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.device.DeviceService
import maestro.device.DeviceService.withPlatform
import maestro.device.Platform
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import maestro.orchestra.SourceLocation
import maestro.utils.CliInsights
import okio.sink
import util.IOSDeviceType
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong

data class FrameCommands(
    val sourceLocation: SourceLocation?,
    val status: CommandStatus,
)

data class Frame(
    val timestamp: Long,
    val commands: List<FrameCommands>,
)

sealed interface Interaction {
    val type: String
    val timestampMs: Long

    data class Tap(
        val x: Float,
        val y: Float,
        override val timestampMs: Long,
    ) : Interaction {
        override val type: String = "tap"
    }

    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        override val timestampMs: Long,
    ) : Interaction {
        override val type: String = "swipe"
    }
}

interface InteractionListener {
    fun onTap(x: Float, y: Float)
    fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
}

class InteractionDriver(
    private val delegate: Driver,
    private val listener: InteractionListener,
) : Driver by delegate {

    private val deviceInfo: DeviceInfo by lazy {
        delegate.deviceInfo()
    }

    override fun tap(point: Point) {
        listener.onTap(point.x / deviceInfo.widthGrid.toFloat(), point.y / deviceInfo.heightGrid.toFloat())
        delegate.tap(point)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        listener.onSwipe(
            startX = start.x / deviceInfo.widthGrid.toFloat(),
            startY = start.y / deviceInfo.heightGrid.toFloat(),
            endX = end.x / deviceInfo.widthGrid.toFloat(),
            endY = end.y / deviceInfo.heightGrid.toFloat(),
            durationMs = durationMs,
        )
        delegate.swipe(start, end, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when (direction) {
            SwipeDirection.UP -> {
                val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                swipe(elementPoint, Point(elementPoint.x, endY), durationMs)
            }

            SwipeDirection.DOWN -> {
                val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                swipe(elementPoint, Point(elementPoint.x, endY), durationMs)
            }

            SwipeDirection.RIGHT -> {
                val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                swipe(elementPoint, Point(endX, elementPoint.y), durationMs)
            }

            SwipeDirection.LEFT -> {
                val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                swipe(elementPoint, Point(endX, elementPoint.y), durationMs)
            }
        }

    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when (swipeDirection) {
            SwipeDirection.UP -> {
                val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                swipe(
                    Point(startX, startY),
                    Point(endX, endY),
                    durationMs,
                )
            }

            SwipeDirection.DOWN -> {
                val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                val startY = (deviceInfo.heightGrid * 0.2f).toInt()
                val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                swipe(
                    Point(startX, startY),
                    Point(endX, endY),
                    durationMs,
                )
            }

            SwipeDirection.RIGHT -> {
                val startX = (deviceInfo.widthGrid * 0.1f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                swipe(
                    Point(startX, startY),
                    Point(endX, endY),
                    durationMs,
                )
            }

            SwipeDirection.LEFT -> {
                val startX = (deviceInfo.widthGrid * 0.9f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                swipe(
                    Point(startX, startY),
                    Point(endX, endY),
                    durationMs,
                )
            }
        }
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }
}

fun main() {
    val outputDir = File("/Users/leland/Downloads/out").apply {
        if (exists()) deleteRecursively()
        mkdirs()
    }
    val screenRecordingFile = File(outputDir, "screen.mp4")
    val framesFile = File(outputDir, "frames.json")
    val interactionsFile = File(outputDir, "interactions.json")
    val frames = mutableListOf<Frame>()
    val interactions = mutableListOf<Interaction>()
    val startTime = AtomicLong()
    val interactionListener = object : InteractionListener {

        override fun onTap(x: Float, y: Float) {
            interactions.add(
                Interaction.Tap(
                    x,
                    y,
                    System.currentTimeMillis() - startTime.get()
                )
            )
        }

        override fun onSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
        ) {
            interactions.add(
                Interaction.Swipe(
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    durationMs = durationMs,
                    timestampMs = System.currentTimeMillis() - startTime.get()
                )
            )
        }
    }
    val runFlow: (Maestro) -> Unit = { maestro ->
        screenRecordingFile.sink().use { sink ->
            maestro.startScreenRecording(sink).use {
                startTime.set(System.currentTimeMillis())
                TestRunner.runSingle(
                    maestro = maestro,
                    device = null,
                    flowFile = File("/Users/leland/test-workspace/Apple Maps.yaml"),
                    env = emptyMap(),
                    resultView = object : ResultView {
                        override fun setState(state: UiState) {
                            when (state) {
                                is UiState.Error -> throw IllegalStateException(state.message)
                                is UiState.Running -> {
                                    frames.add(
                                        Frame(
                                            timestamp = System.currentTimeMillis() - startTime.get(),
                                            commands = state.toFrameCommands(),
                                        )
                                    )
                                }
                            }
                        }
                    },
                    debugOutputPath = Paths.get("/Users/leland/Downloads/maestro-debug"),
                    testOutputDir = null,
                )
            }
        }
    }
//    useMaestroAndroid(interactionListener, runFlow)
    useMaestroIos(interactionListener, runFlow)
    jacksonObjectMapper().writeValue(framesFile, frames)
    jacksonObjectMapper().writeValue(interactionsFile, interactions)
}

fun useMaestroAndroid(interactionListener: InteractionListener, block: (Maestro) -> Unit) {
    Dadb.discover()!!.use { dadb ->
        Maestro.android(InteractionDriver(AndroidDriver(dadb), interactionListener)).use { maestro ->
            block(maestro)
        }
    }
}

fun useMaestroIos(interactionListener: InteractionListener, block: (Maestro) -> Unit) {
    val defaultXctestHost = "127.0.0.1"
    val defaultXcTestPort = 22087
    val reinstallDriver = true

    val deviceId = DeviceService.listConnectedDevices().withPlatform(Platform.IOS).first().instanceId
    
    val driverConfig = IOSDriverConfig(
        prebuiltRunner = false,
        sourceDirectory =  "driver-iPhoneSimulator",
        context = Context.CLI,
        snapshotKeyHonorModalViews = null,
    )
    
    val deviceController = SimctlIOSDevice(
        deviceId = deviceId,
    )

    val xcTestInstaller = LocalXCTestInstaller(
        deviceId = deviceId,
        host = defaultXctestHost,
        defaultPort = defaultXcTestPort,
        reinstallDriver = reinstallDriver,
        deviceType = IOSDeviceType.SIMULATOR,
        iOSDriverConfig = driverConfig,
        deviceController = deviceController
    )

    val xcTestDriverClient = XCTestDriverClient(
        installer = xcTestInstaller,
        client = XCTestClient(defaultXctestHost, defaultXcTestPort),
        reinstallDriver = reinstallDriver,
    )

    val xcTestDevice = XCTestIOSDevice(
        deviceId = deviceId,
        client = xcTestDriverClient,
        getInstalledApps = { XCRunnerCLIUtils.listApps(deviceId) },
    )

    val iosDriver = IOSDriver(
        LocalIOSDevice(
            deviceId = deviceId,
            xcTestDevice = xcTestDevice,
            deviceController = deviceController,
            insights = CliInsights
        ),
        insights = CliInsights
    )

    val maestro = Maestro.ios(
        driver = InteractionDriver(iosDriver, interactionListener),
        openDriver = xcTestDevice.isShutdown(),
    )

    block(maestro)
}

private fun UiState.toFrameCommands(): List<FrameCommands> {
    return when (this) {
        is UiState.Running -> {
            this.onFlowStartCommands.toFrameCommands() +
                    this.commands.toFrameCommands() +
                    this.onFlowCompleteCommands.toFrameCommands()
        }

        is UiState.Error -> throw IllegalStateException(this.message)
    }
}

private fun List<CommandState>?.toFrameCommands(): List<FrameCommands> {
    if (this == null) return emptyList()
    return this.flatMap { commandState ->
        listOf(
            FrameCommands(
                sourceLocation = commandState.command.location,
                status = commandState.status,
            )
        ) +
                commandState.subOnStartCommands.toFrameCommands() +
                commandState.subCommands.toFrameCommands() +
                commandState.subOnCompleteCommands.toFrameCommands()
    }
}
