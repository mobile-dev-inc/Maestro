package maestro.cli.command

import maestro.cli.CliError
import maestro.cli.device.DeviceCreateUtil
import maestro.cli.device.DeviceService
import maestro.cli.device.LocaleConstants
import maestro.cli.device.Platform
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.DeviceConfigAndroid
import maestro.cli.util.DeviceConfigIos
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start-device",
    description = [
        "Starts or creates an iOS Simulator or Android Emulator similar to the ones on Maestro Cloud",
        "Supported device types: iPhone11 (iOS), Pixel 6 (Android)",
    ]
)
class StartDeviceCommand : Callable<Int> {

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
        description = ["OS version to use:", "iOS: 15, 16", "Android: 28, 29, 30, 31, 33"],
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
        TestDebugReporter.install(null)

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

        val (deviceLanguage, deviceCountry) = validateLocaleParams(p)

        DeviceCreateUtil.getOrCreateDevice(p, o, deviceLanguage, deviceCountry, forceCreate).let {
            PrintUtils.message(if (p == Platform.IOS) "Launching simulator..." else "Launching emulator...")
            DeviceService.startDevice(it)
        }

        return 0
    }

    private fun validateLocaleParams(platform: Platform): Pair<String, String> {
        deviceLocale?.let {
            var parts = it.split("_")

            if (parts.size == 2) {
                val language = parts[0]
                val country = parts[1]

                validateLocale(language, country, it, platform)

                return Pair(language, country)
            } else {
                parts = it.split("-")

                if (parts.size == 2) {
                    val language = parts[0]
                    val country = parts[1]

                    validateLocale(language, country, it, platform)

                    return Pair(language, country)
                }

                throw CliError("Wrong device locale format was provided $it. A combination of lowercase ISO-639-1 code and uppercase ISO-3166-1 code should be used, i.e. \"de_DE\" for Germany. More info can be found here https://maestro.mobile.dev/")
            }
        }

        // use en_US locale as a default
        return Pair("en", "US")
    }

    private fun validateLocale(language: String, country: String, fullLocale: String, platform: Platform) {
        when (platform) {
            Platform.IOS -> {
                if (LocaleConstants.findIOSLocale(language, country) == null) {
                    val locales = LocaleConstants.IOS_SUPPORTED_LOCALES.joinToString("\n")
                    throw CliError("$fullLocale locale is currently not supported by Maestro, please check that it is a valid ISO-639-1 + ISO-3166-1 code. Here is a full list of supported locales:\n" +
                            "\n" +
                            locales
                    )
                }
            }
            Platform.ANDROID -> {
                if (!LocaleConstants.ANDROID_SUPPORTED_LANGUAGES.map { it.first }.contains(language)) {
                    val languages = LocaleConstants.ANDROID_SUPPORTED_LANGUAGES.joinToString("\n")
                    throw CliError("$language language is currently not supported by Maestro, please check that it is a valid ISO-639-1 code. Here is a full list of supported languages:\n" +
                            "\n" +
                            languages
                    )
                }

                if (!LocaleConstants.ANDROID_SUPPORTED_COUNTRIES.map { it.first }.contains(country)) {
                    val countries = LocaleConstants.ANDROID_SUPPORTED_COUNTRIES.joinToString("\n")
                    throw CliError("$country country is currently not supported by Maestro, please check that it is a valid ISO-3166-1 code. Here is a full list of supported countries:\n" +
                            "\n" +
                            countries
                    )
                }
            }
            else -> return
        }
    }
}
