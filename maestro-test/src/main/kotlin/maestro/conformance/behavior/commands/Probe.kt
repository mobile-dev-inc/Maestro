package maestro.conformance.behavior.commands

import maestro.conformance.device.Cmd

object Probe {
    fun pidOf(serial: String, appId: String): String =
        Cmd.run("adb", "-s", serial, "shell", "pidof", appId).stdout.trim()
}
