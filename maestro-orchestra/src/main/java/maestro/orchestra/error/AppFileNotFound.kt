package maestro.orchestra.error

import java.nio.file.Path

class AppFileNotFound(
    override val message: String,
    val appPath: Path
): ValidationError(message)
