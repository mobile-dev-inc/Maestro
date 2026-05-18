package maestro.cli.mixin

import picocli.CommandLine

class ReinstallDriverMixin {
    @CommandLine.Option(
        names = ["--reinstall-driver"],
        description = ["Reinstalls driver before running the test. On iOS, reinstalls xctestrunner driver. On Android, reinstalls both driver and server apps. Set to false to skip reinstallation."],
        negatable = true,
        defaultValue = "true",
        fallbackValue = "true"
    )
    var reinstallDriver: Boolean = true
}
