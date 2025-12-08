package maestro.orchestra.util

import java.nio.file.Path

class InvalidFlowFile(
    override val message: String,
    val flowPath: Path
) : RuntimeException()
