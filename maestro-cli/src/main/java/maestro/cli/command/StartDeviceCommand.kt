package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.device.DeviceCreateUtil
import maestro.device.DeviceService
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.device.DeviceSpec
import maestro.device.DeviceSpecRequest
import maestro.device.Platform
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start-device",
    description = [
        "Starts or creates an iOS Simulator or Android Emulator similar to the ones on the cloud",
        "Supported device types: iPhone11 (iOS), Pixel 6 (Android)",
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
        description = ["OS version to use:", "iOS: 16, 17, 18", "Android: 28, 29, 30, 31, 33"],
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
            "iOS: iPhone-11, iPhone-11-Pro, etc. Run command: maestro list-devices",
            "Android: pixel_6, pixel_7, etc. Run command: maestro list-devices"
        ],
    )
    private var deviceModel: String? = null

    @CommandLine.Option(
        order = 4,
        names = ["--device-os"],
        description = [
            "OS version to use:",
            "iOS: iOS-16-2, iOS-17-5, iOS-18-2, etc. maestro list-devices",
            "Android: android-33, android-34, etc. maestro list-devices"
        ],
    )
    private var deviceOs: String? = null

    @CommandLine.Option(
        order = 5,
        names = ["--force-create"],
        description = ["Will override existing device if it already exists"],
    )
    private var forceCreate: Boolean = false

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        if (EnvUtils.isWSL()) {
            throw CliError("This command is not supported in Windows WSL. You can launch your emulator manually.")
        }

        // Get the device configuration
        val parsedPlatform = Platform.fromString(platform)
        val maestroDeviceConfiguration = DeviceSpec.fromRequest(
            when (parsedPlatform) {
                Platform.ANDROID -> DeviceSpecRequest.Android(
                    model = deviceModel,
                    os = deviceOs ?: osVersion.let { "android-$it" },
                    locale = deviceLocale,
                    cpuArchitecture = EnvUtils.getMacOSArchitecture(),
                )
                Platform.IOS -> DeviceSpecRequest.Ios(
                    model = deviceModel,
                    os = deviceOs ?: osVersion.let { "iOS-$it" },
                    locale = deviceLocale,
                )
                Platform.WEB -> DeviceSpecRequest.Web(
                    model = deviceModel,
                    os = deviceOs ?: osVersion,
                    locale = deviceLocale,
                )
                Platform.ROKU -> DeviceSpecRequest.Roku(
                    model = deviceModel,
                    os = deviceOs ?: osVersion,
                    locale = deviceLocale,
                )
            }
        )

        // Get/Create the device
        val device = DeviceCreateUtil.getOrCreateDevice(
            maestroDeviceConfiguration,
            forceCreate
        )

        // Start Device
        DeviceService.startDevice(
            device = device,
            driverHostPort = parent?.port
        )

        return 0
    }
}
