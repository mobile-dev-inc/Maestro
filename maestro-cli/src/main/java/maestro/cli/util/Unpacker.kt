package maestro.cli.util

import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Unpacks files from jar resources.
 */
object Unpacker {

    fun unpack(
        jarPath: String,
        target: File,
    ) {
        Unpacker::class.java.classLoader.getResource(jarPath)?.let { resource ->
            if (target.exists()) {
                if (sameContents(resource, target)) {
                    return
                }
            }

            target.writeBytes(resource.readBytes())
        }
    }

    private fun sameContents(resource: URL, target: File): Boolean {
        return DigestUtils.sha1Hex(resource.openStream()) == DigestUtils.sha1Hex(target.inputStream())
    }

    fun binaryDependency(name: String): File {
        return Paths
            .get(
                System.getProperty("user.home"),
                ".maestro",
                "deps",
                name
            )
            .toAbsolutePath()
            .toFile()
            .also { file ->
                createParentDirectories(file)
                createFileIfDoesNotExist(file)
                grantBinaryPermissions(file)
            }
    }

    private fun createParentDirectories(file: File) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }

    private fun createFileIfDoesNotExist(file: File) {
        if (!file.exists()) {
            if (!file.createNewFile()) {
                error("Unable to create file $file")
            }
        }
    }

    private fun grantBinaryPermissions(file: File) {
        if (isPosixFilesystem()) {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
            )
        }
    }

    private fun isPosixFilesystem() = FileSystems.getDefault()
        .supportedFileAttributeViews()
        .contains("posix")

}