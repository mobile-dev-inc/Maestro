package maestro.orchestra.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import maestro.orchestra.WorkspaceConfig
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.reflect.full.declaredMemberProperties

private val WORKSPACE_CONFIG_KEYS: Set<String> by lazy {
    WorkspaceConfig::class.declaredMemberProperties.map { it.name }.toSet()
}

private val YAML_MAPPER by lazy {
    ObjectMapper(YAMLFactory())
}

fun isFlowFile(path: Path, config: Path?): Boolean {
    if (!path.isRegularFile()) return false // Not a file
    if (path.absolutePathString() == config?.absolutePathString()) return false // Config file
    val extension = path.extension
    if (extension != "yaml" && extension != "yml") return false // Not YAML
    if (path.nameWithoutExtension == "config") return false // Config file

    return !isWorkspaceConfigFile(path)
}

private fun isWorkspaceConfigFile(path: Path): Boolean {
    return try {
        val content = path.readText()
        if (content.contains("\n---")) return false // Flow files have a document separator
        val topLevelKeys = YAML_MAPPER.readValue(content, Map::class.java)?.keys ?: return false
        topLevelKeys.all { it in WORKSPACE_CONFIG_KEYS }
    } catch (e: Exception) {
        false
    }
}
