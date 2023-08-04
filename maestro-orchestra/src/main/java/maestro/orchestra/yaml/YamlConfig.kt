package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAnySetter
import maestro.MaestroException
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.error.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class YamlConfig(
    val name: String?,
    val appId: String,
    val initFlow: YamlInitFlowUnion?,
    val tags: List<String>? = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val onFlowStart: YamlOnFlowStart?,
    val onFlowComplete: YamlOnFlowComplete?,
) {

    private val ext = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowPath: Path): MaestroCommand {
        if (initFlow != null) {
            throw MaestroException.DeprecatedCommand("initFlow command used at: $flowPath is deprecated, please use " +
                    "onFlowStart/onFlowComplete hooks instead. Have a look at the documentation here: " +
                    "https://maestro.mobile.dev/advanced/onflowstart-onflowcomplete-hooks")
        }
        val config = MaestroConfig(
            appId = appId,
            name = name,
            tags = tags,
            initFlow = initFlow(flowPath),
            ext = ext.toMap(),
            onFlowStart = onFlowStart(flowPath),
            onFlowComplete = onFlowComplete(flowPath)
        )
        return MaestroCommand(ApplyConfigurationCommand(config))
    }

    fun getWatchFiles(flowPath: Path): List<Path> {
        if (initFlow == null) return emptyList()
        return when (initFlow) {
            is StringInitFlow -> listOf(getInitFlowPath(flowPath, initFlow.path))
            else -> emptyList()
        }
    }

    private fun initFlow(flowPath: Path): MaestroInitFlow? {
        if (initFlow == null) return null

        val initCommands = when (initFlow) {
            is StringInitFlow -> stringInitCommands(initFlow, flowPath)
            is YamlInitFlow -> initFlow.commands.flatMap { it.toCommands(flowPath, appId) }
        }

        return MaestroInitFlow(
            appId = appId,
            commands = initCommands,
        )
    }

    private fun onFlowComplete(flowPath: Path): MaestroOnFlowComplete? {
        if (onFlowComplete == null) return null

        return MaestroOnFlowComplete(onFlowComplete.commands.flatMap { it.toCommands(flowPath, appId) })
    }

    private fun onFlowStart(flowPath: Path): MaestroOnFlowStart? {
        if (onFlowStart == null) return null

        return MaestroOnFlowStart(onFlowStart.commands.flatMap { it.toCommands(flowPath, appId) })
    }

    companion object {

        private fun stringInitCommands(initFlow: StringInitFlow, flowPath: Path): List<MaestroCommand> {
            val initFlowPath = getInitFlowPath(flowPath, initFlow.path)
            return YamlCommandReader.readCommands(initFlowPath)
        }

        private fun getInitFlowPath(flowPath: Path, initFlowPathString: String): Path {
            val initFlowPath = flowPath.fileSystem.getPath(initFlowPathString)
            val resolvedInitFlowPath = if (initFlowPath.isAbsolute) {
                initFlowPath
            } else {
                flowPath.resolveSibling(initFlowPath).toAbsolutePath()
            }
            if (resolvedInitFlowPath.equals(flowPath.toAbsolutePath())) {
                throw InvalidInitFlowFile("initFlow file can't be the same as the Flow file: ${resolvedInitFlowPath.toUri()}", resolvedInitFlowPath)
            }
            if (!resolvedInitFlowPath.exists()) {
                throw InvalidInitFlowFile("initFlow file does not exist: ${resolvedInitFlowPath.toUri()}", resolvedInitFlowPath)
            }
            if (resolvedInitFlowPath.isDirectory()) {
                throw InvalidInitFlowFile("initFlow file can't be a directory: ${resolvedInitFlowPath.toUri()}", resolvedInitFlowPath)
            }
            return resolvedInitFlowPath
        }
    }
}