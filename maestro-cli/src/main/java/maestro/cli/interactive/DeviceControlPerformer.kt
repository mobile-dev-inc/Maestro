package maestro.cli.interactive

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.orchestra.BackPressCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.StopAppCommand

object DeviceControlPerformer {

    fun launchApp(maestro: Maestro, appId: String) {
        runCommand(
            maestro = maestro,
            command = LaunchAppCommand(
                appId = appId,
                clearState = null,
                clearKeychain = null,
                stopApp = null,
                permissions = null,
                launchArguments = null,
                label = null,
                optional = false,
            )
        )
    }

    fun stopApp(maestro: Maestro, appId: String) {
        runCommand(
            maestro = maestro,
            command = StopAppCommand(
                appId = appId,
                label = null,
                optional = false,
            )
        )
    }

    fun inputText(maestro: Maestro, text: String) {
        runCommand(
            maestro = maestro,
            command = InputTextCommand(
                text = text,
                label = null,
                optional = false,
            )
        )
    }

    fun back(maestro: Maestro) {
        runCommand(
            maestro = maestro,
            command = BackPressCommand(
                label = null,
                optional = false,
            )
        )
    }

    private fun runCommand(maestro: Maestro, command: maestro.orchestra.Command) {
        val orchestra = Orchestra(maestro)
        runBlocking {
            orchestra.runFlow(listOf(MaestroCommand(command = command)))
        }
    }
}
