package maestro.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.toList

object FileUtils {

    /**
     * Zips directory
     *
     * @param from dir to zip
     * @param to output zip file
     */
    fun zipDir(from: Path, to: Path) {
        val stream = to.toFile().outputStream()
        val files = Files.walk(from).filter { !it.isDirectory() }.toList()
        ZipOutputStream(stream).use { zs ->
            try {
                files.forEach {
                    val relativePath = from.relativize(it).toString()
                    val entry = ZipEntry(relativePath)
                    zs.putNextEntry(entry)
                    Files.copy(it, zs)
                    zs.closeEntry()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Unzips file
     *
     * @param from zip file
     * @param to target dir
     */
    fun unzip(from: Path, to: Path) {
        if (!to.exists()) to.createDirectories()
        ZipFile(from.absolutePathString()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val filePath = to.resolve(entry.name)
                    if (!entry.isDirectory) {
                        filePath.parent.createDirectories()
                        Files.copy(input, filePath)
                    } else filePath.createDirectories()
                }
            }
        }
    }

    /**
     * Deletes a directory and all it's contents.
     *
     * WARNING Use with caution!
     **/
    fun deleteDir(dir: Path) {
        Files.walk(dir)
            .map(Path::toFile)
            .sorted(Comparator.reverseOrder())
            .forEach(File::delete)
    }
}