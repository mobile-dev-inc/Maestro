package maestro.cli.util

import java.io.File
import java.util.zip.ZipInputStream

object FileUtils {

    fun File.isZip(): Boolean {
        return try {
            ZipInputStream(inputStream()).close()
            true
        } catch (ignored: Exception) {
            false
        }
    }

}