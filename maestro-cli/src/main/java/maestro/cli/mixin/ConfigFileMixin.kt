package maestro.cli.mixin

import picocli.CommandLine
import java.io.File

class ConfigFileMixin {
    @CommandLine.Option(
        names = ["--config"],
        description = ["Optional YAML configuration file for the workspace. If not provided, Maestro will look for a config.yaml file in the workspace's root directory."]
    )
    var configFile: File? = null
}
