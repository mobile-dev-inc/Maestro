package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonLocation
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.utils.FileAccessScope
import java.nio.file.Path

// Exception for config field validation errors
class ConfigParseError(
    val errorType: String,
    val location: JsonLocation? = null
) : RuntimeException("Config validation error: $errorType")

data class YamlConfig(
    val name: String?,
    // Named `_appId` only because the class exposes a computed `appId` below and Kotlin will not let a
    // constructor parameter and a property share a name. `@JsonProperty` makes `appId` the wire name, so
    // that collision stays an implementation detail: `appId:` is what a flow writes, `_appId:` is not
    // accepted, and a schema derived from this type advertises `appId` rather than the private spelling.
    @JsonProperty("appId") private val _appId: String?,

    val url: String?, // Raw url from YAML - preserved to distinguish web vs app configs
    val tags: List<String>? = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val onFlowStart: YamlOnFlowStart?,
    val onFlowComplete: YamlOnFlowComplete?,
    val properties: Map<String, String> = emptyMap(),
    private val ext: MutableMap<String, Any?> = mutableMapOf<String, Any?>()
) {

    // Computed appId: uses url for web flows, _appId for mobile apps
    // Preserving both fields allows detecting web vs app configuration contexts
    val appId: String

    init {
        if (url == null && _appId == null) {
            throw ConfigParseError("missing_app_target")
        }
        appId = url ?: _appId!!
    }

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowPath: Path, scope: FileAccessScope): MaestroCommand {
        val context = ResolutionContext(flowPath = flowPath, appId = appId, scope = scope)
        val config = MaestroConfig(
            appId = appId,  // maestro-cli uses url as appId for web flows
            name = name,
            tags = tags,
            ext = ext.toMap(),
            onFlowStart = onFlowStart(context),
            onFlowComplete = onFlowComplete(context),
            properties = properties
        )
        return MaestroCommand(ApplyConfigurationCommand(config))
    }

    private fun onFlowComplete(context: ResolutionContext): MaestroOnFlowComplete? {
        if (onFlowComplete == null) return null

        return MaestroOnFlowComplete(onFlowComplete.commands.flatMap { it.toCommands(context) })
    }

    private fun onFlowStart(context: ResolutionContext): MaestroOnFlowStart? {
        if (onFlowStart == null) return null

        return MaestroOnFlowStart(onFlowStart.commands.flatMap { it.toCommands(context) })
    }
}
