package maestro.cli.util

import maestro.orchestra.yaml.YamlCommandReader
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

    fun File.isWebFlow(): Boolean {
        val config = YamlCommandReader.readConfig(toPath())
        return Regex("http(s?)://").containsMatchIn(config.appId)
    }

}