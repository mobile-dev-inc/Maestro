package maestro.utils

import java.nio.file.Files
import java.nio.file.Path

object TemporaryDirectory {

    fun use(block: (tmpDir: Path) -> Unit) {
        val tmpDir = Files.createTempDirectory(null)
        try {
            block(tmpDir)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
