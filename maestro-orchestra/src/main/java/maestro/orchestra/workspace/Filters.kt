package maestro.orchestra.workspace

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

fun isFlowFile(path: Path, config: Path?): Boolean {
    if (!path.isRegularFile()) return false // Not a file
    if (path.absolutePathString() == config?.absolutePathString()) return false // Config file
    val extension = path.extension
    if (extension != "yaml" && extension != "yml") return false // Not YAML
    if (path.nameWithoutExtension == "config") return false // Config file
    if (path.nameWithoutExtension.endsWith("_config")) return false // Additional config file (e.g. regression_config.yaml)
    if (path.nameWithoutExtension.endsWith("-config")) return false // Additional config file (e.g. regression-config.yaml)
    return true
}