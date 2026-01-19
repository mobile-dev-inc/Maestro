package maestro.cli.command

import maestro.Platform
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.device.DeviceCreateUtil
import maestro.device.DeviceService
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.device.DeviceCatalog
import maestro.locale.LocaleValidationException
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
        description = ["Platforms: android, ios"],
    )
    private lateinit var platform: String

    @CommandLine.Option(
        order = 1,
        names = ["--os-version"],
        description = ["OS version to use:", "iOS: 16, 17, 18", "Android: 28, 29, 30, 31, 33"],
    )
    private lateinit var osVersion: String

    @CommandLine.Option(
        order = 2,
        names = ["--device-locale"],
        description = ["a combination of lowercase ISO-639-1 code and uppercase ISO-3166-1 code i.e. \"de_DE\" for Germany"],
    )
    private var deviceLocale: String? = null

    @CommandLine.Option(
        order = 4,
        names = ["--force-create"],
        description = ["Will override existing device if it already exists"],
    )
    private var forceCreate: Boolean = false

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        if (EnvUtils.isWSL()) {
            throw CliError("This command is not supported in Windows WSL. You can launch your emulator manually.")
        }

        val p = Platform.fromString(platform)
            ?: throw CliError("Unsupported platform $platform. Please specify one of: android, ios")

        val maestroPlatform = when(p) {
            Platform.ANDROID -> maestro.Platform.ANDROID
            Platform.IOS -> maestro.Platform.IOS
            Platform.WEB -> maestro.Platform.WEB
        }

        try {
            val deviceSpec = DeviceCatalog.getDeviceSpecs(
                platform = maestroPlatform,
                locale = deviceLocale,
                osVersion = if (::osVersion.isInitialized) osVersion.toIntOrNull() else null
            )

            DeviceCreateUtil.getOrCreateDevice(p, deviceSpec).let { device ->
                PrintUtils.message(if (p == Platform.IOS) "Launching simulator..." else "Launching emulator...")
                DeviceService.startDevice(
                    device = device,
                    driverHostPort = parent?.port
                )
            }
        } catch (e: LocaleValidationException) {
            throw CliError(e.message.toString())
        }

        return 0
    }
}
