package maestro.cli.command

import maestro.cli.util.PrintUtils.err
import maestro.cli.util.PrintUtils.info
import maestro.cli.util.PrintUtils.success
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "setup-ios-device",
    description = ["Check the local requirements for running Maestro on a physical iOS device."],
)
class SetupIOSDeviceCommand : Callable<Int> {

    @CommandLine.Option(
        names = ["--install-dependencies"],
        description = ["Install missing system dependencies with Homebrew."],
    )
    private var installDependencies: Boolean = false

    override fun call(): Int {
        val result = IOSDeviceSetup().run(installDependencies)

        result.checks.forEach { check ->
            if (check.succeeded) {
                success("✓ ${check.name}: ${check.detail}")
            } else {
                err("✗ ${check.name}: ${check.detail}")
            }
        }

        result.nextStep?.let { info("\n$it") }
        return if (result.isReady) 0 else 1
    }
}
