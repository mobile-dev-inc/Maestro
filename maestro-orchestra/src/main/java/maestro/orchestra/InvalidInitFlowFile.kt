package maestro.orchestra

import java.io.File

class InvalidInitFlowFile(
    val initFlowFile: File
) : RuntimeException()