package maestro.cli.session

import maestro.cli.CliError
import maestro.device.Platform

fun validateAndroidUser(androidUser: Int?, platform: Platform): Int? {
    if (androidUser == null) return null
    if (androidUser < 0) throw CliError("--android-user must be 0 or greater")
    if (platform != Platform.ANDROID) throw CliError("--android-user is only supported on Android devices")
    return androidUser
}
