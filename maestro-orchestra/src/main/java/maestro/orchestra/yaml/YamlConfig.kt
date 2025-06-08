package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonAnySetter
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import java.nio.file.Path

data class YamlConfig(
    val name: String?,
    @JsonAlias("url")
    val appId: String,
    val tags: List<String>? = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val onFlowStart: YamlOnFlowStart?,
    val onFlowComplete: YamlOnFlowComplete?,
    private val ext: MutableMap<String, Any?> = mutableMapOf<String, Any?>()
) {

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowPath: Path): MaestroCommand {
        val config = MaestroConfig(
            appId = appId,
            name = name,
            tags = tags,
            ext = ext.toMap(),
            onFlowStart = onFlowStart(flowPath),
            onFlowComplete = onFlowComplete(flowPath)
        )
        return MaestroCommand(ApplyConfigurationCommand(config))
    }

    private fun onFlowComplete(flowPath: Path): MaestroOnFlowComplete? {
        if (onFlowComplete == null) return null

        return MaestroOnFlowComplete(onFlowComplete.commands.flatMap { it.toCommands(flowPath, appId) })
    }

    private fun onFlowStart(flowPath: Path): MaestroOnFlowStart? {
        if (onFlowStart == null) return null

        return MaestroOnFlowStart(onFlowStart.commands.flatMap { it.toCommands(flowPath, appId) })
    }
}
