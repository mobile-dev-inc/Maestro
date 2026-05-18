package maestro.cli.mixin

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec

class DeviceConfigMixin {

    @CommandLine.Option(
        names = ["--device-locale"],
        description = ["Locale that will be set to a device, ISO-639-1 code and uppercase ISO-3166-1 code i.e. \"de_DE\" for Germany"],
    )
    var deviceLocale: String? = null

    @CommandLine.Option(
        names = ["--device-model"],
        description = ["Device model to run your flow against."],
    )
    var deviceModel: String? = null

    @CommandLine.Option(
        names = ["--device-os"],
        description = ["OS version to run your flow against."],
    )
    var deviceOs: String? = null

    @CommandLine.Spec(CommandLine.Spec.Target.MIXEE)
    fun setMixee(mixee: CommandSpec) {
        val listCmd = if (mixee.name() == "cloud") "maestro list-cloud-devices" else "maestro list-devices"
        replaceDescription(
            mixee, "--device-model",
            "Device model to run your flow against.",
            "  iOS: iPhone-11, iPhone-17-Pro, etc. Run command: $listCmd",
            "  Android: pixel_6, pixel_7, etc. Run command: $listCmd",
        )
        replaceDescription(
            mixee, "--device-os",
            "OS version to run your flow against.",
            "  iOS: iOS-18-2, iOS-26-2, etc. $listCmd",
            "  Android: android-33, android-34, etc. $listCmd",
        )
    }

    private fun replaceDescription(mixee: CommandSpec, optionName: String, vararg lines: String) {
        val existing = mixee.findOption(optionName) ?: return
        mixee.remove(existing)
        mixee.addOption(OptionSpec.builder(existing).description(*lines).build())
    }
}
