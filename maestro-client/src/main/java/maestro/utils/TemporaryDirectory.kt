package maestro.utils

import java.nio.file.Files
import java.nio.file.Path

object TemporaryDirectory {

    inline fun <T> use(block: (tmpDir: Path) -> T): T {
        val tmpDir = Files.createTempDirectory(null)
        return try {
            block(tmpDir)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
