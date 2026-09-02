package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.device.DeviceCreateUtil
import maestro.device.DeviceService
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.locale.AndroidLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.WebLocale
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

    internal fun buildDeviceSpec(
        parsedPlatform: Platform,
        // Injected so tests stay deterministic; production resolves against sdkmanager.
        resolveImage: (String, CPU_ARCHITECTURE) -> String? = DeviceService::resolveSystemImage,
    ): DeviceSpec = when (parsedPlatform) {
        Platform.ANDROID -> {
            val default = DeviceSpec.Android.DEFAULT
            val arch = EnvUtils.getMacOSArchitecture()
            val isFullImage = deviceOs?.startsWith("system-images;") == true
            // Requested os: a full-image's 2nd segment, else --device-os, --os-version, then default.
            val requestedOs = if (isFullImage) deviceOs!!.split(";")[1]
                              else deviceOs ?: osVersion?.let { "android-$it" } ?: default.os
            // Decide the concrete image now so the spec itself is the device contract. A resolved image
            // may carry a minor-versioned platform (android-37.1), so adopt it as the spec's os too.
            val image = if (isFullImage) deviceOs else resolveImage(requestedOs, arch)
            DeviceSpec.Android(
                model = deviceModel ?: default.model,
                os = image?.split(";")?.get(1) ?: requestedOs,
                systemImageOverride = image,
                locale = deviceLocale?.let { AndroidLocale.fromString(it) } ?: default.locale,
                cpuArchitecture = arch,
            )
        }
        Platform.IOS -> {
            val default = DeviceSpec.Ios.DEFAULT
            DeviceSpec.Ios(
                model = deviceModel ?: default.model,
                os = deviceOs ?: osVersion?.let { "iOS-$it" } ?: default.os,
                locale = deviceLocale?.let { IosLocale.fromString(it) } ?: default.locale,
            )
        }
        Platform.WEB -> {
            val default = DeviceSpec.Web.DEFAULT
            DeviceSpec.Web(
                model = deviceModel ?: default.model,
                os = deviceOs ?: osVersion ?: default.os,
                locale = deviceLocale?.let { WebLocale.fromString(it) } ?: default.locale,
            )
        }
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
