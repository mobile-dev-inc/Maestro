package maestro.cli.mixin

import maestro.cli.util.EnvUtils.BASE_API_URL
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.OptionSpec

class ApiClientMixin {

    @CommandLine.Option(
        names = ["--api-key", "--apiKey"],
        description = ["Maestro Cloud API key"],
    )
    var apiKey: String? = null

    @CommandLine.Option(
        names = ["--api-url", "--apiUrl"],
        description = ["Maestro Cloud API base URL"],
    )
    var apiUrl: String = BASE_API_URL

    @CommandLine.Spec(CommandLine.Spec.Target.MIXEE)
    fun setMixee(mixee: CommandSpec) {
        if (mixee.name() == "test") {
            replaceDescription(mixee, "--api-key", "Maestro Cloud API key (used with --analyze)")
            replaceDescription(mixee, "--api-url", "Maestro Cloud API base URL (used with --analyze)")
        }
    }

    private fun replaceDescription(mixee: CommandSpec, optionName: String, vararg lines: String) {
        val existing = mixee.findOption(optionName) ?: return
        mixee.remove(existing)
        mixee.addOption(OptionSpec.builder(existing).description(*lines).build())
    }
}
