package maestro.orchestra.workspace

import maestro.orchestra.yaml.YamlCommandReader
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

fun isFlowFile(path: Path, config: Path?): Boolean {
    if (!path.isRegularFile()) return false // Not a file
    if (path.absolutePathString() == config?.absolutePathString()) return false // Config file
    val extension = path.extension
    if (extension != "yaml" && extension != "yml") return false // Not YAML
    if (path.nameWithoutExtension == "config") return false // Config file

    return !isWorkspaceConfigFile(path)
}

fun isWorkspaceConfigFile(path: Path): Boolean {
    return try {
        val content = path.readText()
        if (content.contains("\n---")) return false // Flow files have a document separator
        YamlCommandReader.findUnknownWorkspaceConfigKeys(content)?.isEmpty() ?: false
    } catch (e: Exception) {
        false
    }
}
