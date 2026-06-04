package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.core.JsonLocation
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.CustomCommandArgument
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.orchestra.error.SyntaxError
import java.nio.file.Path

// Exception for config field validation errors
class ConfigParseError(
    val errorType: String,
    val location: JsonLocation? = null
) : RuntimeException("Config validation error: $errorType")

data class YamlConfig(
    val name: String?,
    @JsonAlias("appId") private val _appId: String?,

    val url: String?, // Raw url from YAML - preserved to distinguish web vs app configs
    val tags: List<String>? = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val onFlowStart: YamlOnFlowStart?,
    val onFlowComplete: YamlOnFlowComplete?,
    val properties: Map<String, String> = emptyMap(),
    val command: String? = null,
    val arguments: List<YamlFlowArgument>? = null,
    private val ext: MutableMap<String, Any?> = mutableMapOf<String, Any?>()
) {

    // Computed appId: uses url for web flows, _appId for mobile apps
    // Preserving both fields allows detecting web vs app configuration contexts
    // Custom-command definition files may omit the app target since they are not runnable flows.
    val appId: String

    init {
        if (url == null && _appId == null && command == null) {
            throw ConfigParseError("missing_app_target")
        }
        if (arguments != null && command == null) {
            throw SyntaxError("`arguments` declared without `command` name")
        }
        appId = url ?: _appId ?: ""
    }

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowPath: Path): MaestroCommand {
        val config = MaestroConfig(
            appId = appId,  // maestro-cli uses url as appId for web flows
            name = name,
            tags = tags,
            ext = ext.toMap(),
            onFlowStart = onFlowStart(flowPath),
            onFlowComplete = onFlowComplete(flowPath),
            properties = properties,
            command = command,
            arguments = toCustomCommandArguments(),
        )
        return MaestroCommand(ApplyConfigurationCommand(config))
    }

    fun toCustomCommandArguments(): List<CustomCommandArgument>? {
        if (arguments == null) return null
        val ownerCommand = command ?: return null
        val seen = mutableSetOf<String>()
        return arguments.map { arg ->
            if (!seen.add(arg.name)) {
                throw SyntaxError("Duplicate argument '${arg.name}' on command '$ownerCommand'")
            }
            arg.toCustomCommandArgument(ownerCommand)
        }
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
