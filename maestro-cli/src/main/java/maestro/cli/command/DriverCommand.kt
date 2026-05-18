package maestro.cli.command

import maestro.cli.mixin.AppleTeamIdMixin
import maestro.cli.driver.DriverBuilder
import maestro.cli.driver.RealIOSDeviceDriver
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "driver-setup",
    description = [
        "Setup maestro drivers on your devices. Right now works for real iOS devices"
    ],
    hidden = true
)
class DriverCommand : Callable<Int> {

    @CommandLine.Mixin
    var appleTeamIdMixin = AppleTeamIdMixin()

    @CommandLine.Option(
        names = ["--destination"],
        description = ["Destination device to build the driver for. Defaults to generic/platform=iphoneos if not specified."],
        hidden = true
    )
    private var destination: String? = null


    override fun call(): Int {
        val teamId = requireNotNull(appleTeamIdMixin.appleTeamId) { "Apple account team ID must be specified." }
        val destination = destination ?: "generic/platform=iphoneos"

        val driverBuilder = DriverBuilder()

        RealIOSDeviceDriver(
            teamId = teamId,
            destination = destination,
            driverBuilder = driverBuilder,
        ).validateAndUpdateDriver(force = true)

        return 0
    }
}