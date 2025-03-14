package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.device.DeviceCreateUtil
import maestro.device.DeviceService
import maestro.device.Platform
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.DeviceConfigAndroid
import maestro.cli.util.DeviceConfigIos
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.utils.LocaleUtils
import maestro.utils.LocaleValidationAndroidCountryException
import maestro.utils.LocaleValidationAndroidLanguageException
import maestro.utils.LocaleValidationIosException
import maestro.utils.LocaleValidationNotSupportedPlatformException
import maestro.utils.LocaleValidationWrongLocaleFormatException
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
        description = ["OS version to use:", "iOS: 15, 16, 17", "Android: 28, 29, 30, 31, 33"],
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

        // default OS version
        if (!::osVersion.isInitialized) {
            osVersion = when (p) {
                Platform.IOS -> DeviceConfigIos.defaultVersion.toString()
                Platform.ANDROID -> DeviceConfigAndroid.defaultVersion.toString()
                else -> ""
            }
        }
        val o = osVersion.toIntOrNull()

        val maestroPlatform = when(p) {
            Platform.ANDROID -> maestro.Platform.ANDROID
            Platform.IOS -> maestro.Platform.IOS
            Platform.WEB -> maestro.Platform.WEB
        }

        val locale = deviceLocale ?: "en_US"

        try {
            val (deviceLanguage, deviceCountry) = LocaleUtils.parseLocaleParams(locale, maestroPlatform)

            DeviceCreateUtil.getOrCreateDevice(p, o, deviceLanguage, deviceCountry, forceCreate).let { device ->
                PrintUtils.message(if (p == Platform.IOS) "Launching simulator..." else "Launching emulator...")
                DeviceService.startDevice(
                    device = device,
                    driverHostPort = parent?.port
                )
            }
        } catch (e: LocaleValidationIosException) {
            val locales = LocaleUtils.IOS_SUPPORTED_LOCALES.joinToString("\n")
            throw CliError("$deviceLocale locale is currently not supported by Maestro, please check that it is a valid ISO-639-1 + ISO-3166-1 code. Here is a full list of supported locales:\n" +
                    "\n" +
                    locales
            )
        } catch (e: LocaleValidationAndroidLanguageException) {
            val languages = LocaleUtils.ANDROID_SUPPORTED_LANGUAGES.joinToString("\n")
            throw CliError("${e.language} language is currently not supported by Maestro, please check that it is a valid ISO-639-1 code. Here is a full list of supported languages:\n" +
                    "\n" +
                    languages
            )
        } catch (e: LocaleValidationAndroidCountryException) {
            val countries = LocaleUtils.ANDROID_SUPPORTED_COUNTRIES.joinToString("\n")
            throw CliError("${e.country} country is currently not supported by Maestro, please check that it is a valid ISO-3166-1 code. Here is a full list of supported countries:\n" +
                    "\n" +
                    countries
            )
        } catch(e: LocaleValidationWrongLocaleFormatException) {
            throw CliError("Wrong device locale format was provided $deviceLocale. A combination of lowercase ISO-639-1 code and uppercase ISO-3166-1 code should be used, i.e. \"de_DE\" for Germany. More info can be found here https://maestro.mobile.dev/")
        } catch (e: LocaleValidationNotSupportedPlatformException) {
            throw CliError("The feature to set a device locale is not supported by the platform ${p.description}")
        }

        return 0
    }
}
