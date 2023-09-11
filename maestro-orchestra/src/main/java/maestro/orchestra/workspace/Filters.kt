package maestro.orchestra.workspace

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

fun isFlowFile(path: Path): Boolean {
    if (!path.isRegularFile()) return false // Not a file
    val extension = path.extension
    if (extension != "yaml" && extension != "yml") return false // Not YAML
    if (path.nameWithoutExtension == "config") return false // Config file
    return true
}