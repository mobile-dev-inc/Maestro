package maestro.cli.util

import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.StringUtils.toRegexSafe
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
        if (isDirectory) {
            return listFiles()
                ?.any { it.isWebFlow() }
                ?: false
        }

        val isYaml =
            name.endsWith(".yaml", ignoreCase = true) ||
            name.endsWith(".yml", ignoreCase = true)

        if (
            !isYaml ||
            name.equals("config.yaml", ignoreCase = true) ||
            name.equals("config.yml", ignoreCase = true)
        ) {
            return false
        }

        val config = YamlCommandReader.readConfig(toPath())
        return config.url != null
    }

}
