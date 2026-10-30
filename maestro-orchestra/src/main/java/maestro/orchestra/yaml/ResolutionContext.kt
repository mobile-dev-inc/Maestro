package maestro.orchestra.yaml

import maestro.utils.FileAccessScope
import java.nio.file.Path

/**
 * The values the flow parser threads through command resolution: the flow file
 * being parsed, its app id, and the scope that resolves referenced file paths.
 */
data class ResolutionContext(
    val flowPath: Path,
    val appId: String,
    val scope: FileAccessScope,
)
