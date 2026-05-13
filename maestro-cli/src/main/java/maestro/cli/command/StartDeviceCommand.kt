package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DeviceConfigMixin
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

    @CommandLine.Mixin
    var deviceConfigMixin = DeviceConfigMixin()

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
        val deviceSpec: DeviceSpec = when (parsedPlatform) {
            Platform.ANDROID -> {
                val default = DeviceSpec.Android.DEFAULT
                DeviceSpec.Android(
                    // osVersion is nullable; ?.let prevents interpolating "android-null"
                    model = deviceConfigMixin.deviceModel ?: default.model,
                    os = deviceConfigMixin.deviceOs ?: osVersion?.let { "android-$it" } ?: default.os,
                    // AndroidLocale is a data class (no pre-defined constant); parse the default
                    locale = deviceConfigMixin.deviceLocale?.let { AndroidLocale.fromString(it) } ?: default.locale,
                    cpuArchitecture = EnvUtils.getMacOSArchitecture(),
                )
            }
            Platform.IOS -> {
                val default = DeviceSpec.Ios.DEFAULT
                DeviceSpec.Ios(
                    model = deviceConfigMixin.deviceModel ?: default.model,
                    os = deviceConfigMixin.deviceOs ?: osVersion?.let { "iOS-$it" } ?: default.os,
                    locale = deviceConfigMixin.deviceLocale?.let { IosLocale.fromString(it) } ?: default.locale,
                )
            }
            Platform.WEB -> {
                val default = DeviceSpec.Web.DEFAULT
                DeviceSpec.Web(
                    model = deviceConfigMixin.deviceModel ?: default.model,
                    os = deviceConfigMixin.deviceOs ?: osVersion ?: default.os,
                    locale = deviceConfigMixin.deviceLocale?.let { WebLocale.fromString(it) } ?: default.locale,
                )
            }
        }

        // Get/Create the device
        val device = DeviceCreateUtil.getOrCreateDevice(
            deviceSpec,
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
