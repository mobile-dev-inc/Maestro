package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.device.DeviceCreateUtil
import maestro.cli.device.DevicePicker
import maestro.cli.device.LocalDevices
import maestro.cli.util.PrintUtils
import maestro.device.DeviceService
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.device.DeviceSpec
import maestro.device.Platform
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start-device",
    description = [
        "Starts or creates an iOS Simulator or Android Emulator similar to the ones in Maestro Cloud"
    ]
)
class StartDeviceCommand : Callable<Int> {

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        order = 0,
        names = ["--platform"],
        required = true,
        description = ["Platforms: android, ios, web"],
    )
    private lateinit var platform: String

    @Deprecated("Use --device-os instead")
    @CommandLine.Option(
        order = 1,
        hidden = true,
        names = ["--os-version"],
        description = ["OS version to use:", "iOS: 16, 17, 18, 26", "Android: 28, 29, 30, 31, 33, 34"],
    )
    private var osVersion: String? = null

    @CommandLine.Option(
        order = 2,
        names = ["--device-locale"],
        description = ["a combination of lowercase ISO-639-1 code and uppercase ISO-3166-1 code i.e. \"de_DE\" for Germany"],
    )
    private var deviceLocale: String? = null

    @CommandLine.Option(
        order = 3,
        names = ["--device-model"],
        description = [
            "Device model to run against",
            "  iOS: iPhone-11, iPhone-17-Pro, etc. Run command: maestro list-devices",
            "  Android: pixel_6, pixel_7, etc. Run command: maestro list-devices"
        ],
    )
    private var deviceModel: String? = null

    @CommandLine.Option(
        order = 4,
        names = ["--device-os"],
        description = [
            "OS version to use, or a full Android system image:",
            "  iOS: iOS-18-2, iOS-26-2 etc. maestro list-devices",
            "  Android: android-33, android-34, etc. maestro list-devices",
            "  Android (full image): system-images;android-34;google_apis_playstore;arm64-v8a",
        ],
    )
    private var deviceOs: String? = null

    @CommandLine.Option(
        order = 6,
        names = ["--force-create"],
        description = ["Will override existing device if it already exists"],
    )
    private var forceCreate: Boolean = false

    /**
     * Whatever the flags leave out is picked from what this machine can create ([LocalDevices]):
     * the newest iPhone or Pixel and its newest OS. Both given are used as given, without a list.
     */
    internal fun buildDeviceSpec(
        parsedPlatform: Platform,
        localDevices: (Platform, String?) -> Map<String, List<String>> = LocalDevices::forPlatform,
    ): DeviceSpec {
        val systemImage = deviceOs?.takeIf { parsedPlatform == Platform.ANDROID && it.startsWith("system-images;") }
        val os = systemImage?.split(";")?.get(1) ?: deviceOs ?: osVersion?.let {
            when (parsedPlatform) {
                Platform.ANDROID -> "android-$it"
                Platform.IOS -> "iOS-$it"
                Platform.WEB -> it
            }
        }
        val model = deviceModel
        val device = if (model != null && os != null) {
            DevicePicker.Picked(model, os)
        } else {
            DevicePicker.pick(parsedPlatform, model, os, LocalDevices.SOURCE) { localDevices(parsedPlatform, systemImage) }
                .also {
                    if (parsedPlatform != Platform.WEB) {
                        PrintUtils.message("Using ${it.model} / ${it.os}. Pass --device-model and --device-os to choose the device.")
                    }
                }
        }
        return DevicePicker.toDeviceSpec(
            parsedPlatform,
            device,
            deviceLocale,
            systemImage = systemImage,
            cpuArchitecture = EnvUtils.getMacOSArchitecture(),
        )
    }

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        if (EnvUtils.isWSL()) {
            throw CliError("This command is not supported in Windows WSL. You can launch your emulator manually.")
        }

        // Get the device configuration
        val parsedPlatform = Platform.fromString(platform)
        val deviceSpec: DeviceSpec = buildDeviceSpec(parsedPlatform)

        // Get/Create the device
        val device = DeviceCreateUtil.getOrCreateDevice(
            deviceSpec,
            forceCreate
        )

        // Snapshot the devices that are already connected so the freshly launched emulator can be
        // told apart from them. Without this, locale setup may be applied to a pre-existing device
        // (e.g. a physical phone or another emulator) instead of the one we just started (#2209).
        val connectedDevices = DeviceService.listConnectedDevices(
            host = parent?.host,
            port = parent?.port,
        ).map { it.instanceId }.toSet()

        // Start Device
        DeviceService.startDevice(
            device = device,
            driverHostPort = parent?.port,
            connectedDevices = connectedDevices,
        )

        return 0
    }
}
