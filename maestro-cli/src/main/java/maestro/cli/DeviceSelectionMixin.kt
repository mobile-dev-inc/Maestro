package maestro.cli

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec

class DeviceSelectionMixin {
    @CommandLine.Option(names = ["-p", "--platform"], description = ["Select a platform to run on"])
    var platform: String? = null

    @CommandLine.Option(
        names = ["--device", "--udid"],
        description = ["Device ID to run on explicitly"],
    )
    var deviceId: String? = null

    @CommandLine.Option(names = ["--driver-host-port"], hidden = true)
    var driverHostPort: Int? = null

    @CommandLine.Spec(CommandLine.Spec.Target.MIXEE)
    fun setMixee(mixee: CommandSpec) {
        if (mixee.name() == "test") {
            val existing = mixee.findOption("--device") ?: return
            mixee.remove(existing)
            mixee.addOption(
                OptionSpec.builder(existing)
                    .description("Device ID to run on explicitly, can be a comma separated list of IDs: --device \"Emulator_1,Emulator_2\"")
                    .build()
            )
        }
    }
}
