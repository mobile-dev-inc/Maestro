package maestro.cli

import picocli.CommandLine

class DeviceSelectionMixin {
    @CommandLine.Option(names = ["-p", "--platform"], description = ["Select a platform to run on"])
    var platform: String? = null

    @CommandLine.Option(
        names = ["--device", "--udid"],
        description = ["Device ID to run on explicitly, can be a comma separated list of IDs: --device \"Emulator_1,Emulator_2\" "],
    )
    var deviceId: String? = null

    @CommandLine.Option(names = ["--driver-host-port"], hidden = true)
    var driverHostPort: Int? = null
}
