package maestro.cli.util

import maestro.orchestra.workspace.isWorkspaceConfigFile
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.StringUtils.toRegexSafe
import java.io.File
import java.nio.file.Path
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

        if (!isYaml || isWorkspaceConfigFile(toPath())) {
            return false
        }

        val config = YamlCommandReader.readConfig(toPath())
        return config.url != null
    }

    /** Returns this path relative to [WorkingDirectory.baseDir] when possible, otherwise the absolute path string. */
    fun Path.toCwdRelativeOrAbsoluteString(): String {
        val absolute = toAbsolutePath().normalize()
        val cwd = WorkingDirectory.baseDir.toPath().toAbsolutePath().normalize()
        val relative = runCatching { cwd.relativize(absolute) }.getOrNull()
        return relative
            ?.takeIf { it.toString().isNotEmpty() && !it.startsWith("..") }
            ?.toString()
            ?: absolute.toString()
    }

}
