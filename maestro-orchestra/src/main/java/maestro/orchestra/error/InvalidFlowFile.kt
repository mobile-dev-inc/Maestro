package maestro.orchestra.error

import java.nio.file.Path

class InvalidFlowFile(
    override val message: String,
    val flowPath: Path
) : RuntimeException()