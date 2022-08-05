package maestro.orchestra

import java.nio.file.Path

class InvalidInitFlowFile(
    override val message: String,
    val initFlowPath: Path
) : RuntimeException()