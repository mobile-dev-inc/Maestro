package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAnySetter
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.error.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroInitFlow
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class YamlConfig(
    val name: String?,
    val appId: String,
    val initFlow: YamlInitFlowUnion?,
) {

    private val ext = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowPath: Path): MaestroCommand {
        val config = MaestroConfig(
            appId = appId,
            name = name,
            initFlow = initFlow(flowPath),
            ext = ext.toMap()
        )
        return MaestroCommand(applyConfigurationCommand = ApplyConfigurationCommand(config))
    }

    fun getInitFlowPath(flowPath: Path): Path? {
        if (initFlow == null) return null
        return when (initFlow) {
            is StringInitFlow -> getInitFlowPath(flowPath, initFlow.path)
            is YamlInitFlow -> null
        }
    }

    private fun initFlow(flowPath: Path): MaestroInitFlow? {
        if (initFlow == null) return null

        val initCommands = when (initFlow) {
            is StringInitFlow -> stringInitCommands(initFlow, flowPath)
            is YamlInitFlow -> initFlow.commands.map { it.toCommand(appId) }
        }

        return MaestroInitFlow(
            appId = appId,
            commands = initCommands,
        )
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