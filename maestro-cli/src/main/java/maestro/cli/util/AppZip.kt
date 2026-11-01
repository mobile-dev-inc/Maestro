package maestro.cli.util

import com.hominux.compress4j.archivers.zip.ZipArchiveCreator
import java.io.File
import java.nio.file.Path

/** Zips [appFile], a file or an app bundle directory, into [tmpDir] and returns the archive. */
internal fun zipApp(appFile: File, tmpDir: Path): File {
    val zip = tmpDir.resolve(appFile.name + ".zip")
    ZipArchiveCreator.builder(zip).build().use { creator ->
        if (appFile.isDirectory) {
            creator.addDirectoryRecursively(appFile.name, appFile.toPath())
        } else {
            creator.addFile(appFile.toPath())
        }
    }
    return zip.toFile()
}
