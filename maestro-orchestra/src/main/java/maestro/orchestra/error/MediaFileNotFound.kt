package maestro.orchestra.error

import java.nio.file.Path

class MediaFileNotFound(
    override val message: String,
    val mediaPath: Path
): ValidationError(message)