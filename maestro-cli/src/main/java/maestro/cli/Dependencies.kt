package maestro.cli

import maestro.cli.util.Unpacker.binaryDependency
import maestro.cli.util.Unpacker.unpack
import maestro.cli.util.EnvUtils

object Dependencies {
    private val appleSimUtils = binaryDependency("applesimutils")
    private val simulatorServer = binaryDependency(simulatorServerBinaryName())

    fun install() {
        unpack(
            jarPath = "deps/applesimutils",
            target = appleSimUtils,
        )
    }

    fun installSimulatorServer() {
        unpack(
            jarPath = "deps/simulator-server/${simulatorServerPlatformDir()}/${simulatorServerBinaryName()}",
            target = simulatorServer,
        )
    }

    fun simulatorServerBinary() = simulatorServer

    private fun simulatorServerBinaryName(): String =
        if (EnvUtils.isWindows()) "simulator-server.exe" else "simulator-server"

    private fun simulatorServerPlatformDir(): String =
        when {
            EnvUtils.isWindows() -> "windows"
            EnvUtils.OS_NAME.lowercase().let { it.contains("mac") || it.contains("darwin") } -> "darwin"
            else -> "linux"
        }

}
