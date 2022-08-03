package maestro.orchestra

import java.io.File

class InvalidInitFlowFile(
    override val message: String,
    val initFlowFile: File
) : RuntimeException()