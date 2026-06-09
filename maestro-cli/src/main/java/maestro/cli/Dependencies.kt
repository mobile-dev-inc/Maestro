package maestro.cli

import maestro.cli.util.Unpacker.binaryDependency
import maestro.cli.util.Unpacker.unpack
import maestro.cli.util.Unpacker.unpackTree
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
        // simulator-server expects a sibling `resources/` tree (screen-sharing-agent.jar
        // + per-ABI .so files for physical Android; ffmpeg .so's for linux), so unpack
        // the whole platform subtree rather than just the binary.
        val binaryName = simulatorServerBinaryName()
        unpackTree(
            classpathPrefix = "deps/simulator-server/${simulatorServerPlatformDir()}",
            targetDir = simulatorServer.parentFile,
            executableEntries = setOf(binaryName),
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
