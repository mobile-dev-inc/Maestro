package maestro.cli.util

import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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

    /**
     * Unpack every classpath resource under [classpathPrefix] into [targetDir], preserving
     * the relative directory layout. Files whose path relative to [classpathPrefix] is in
     * [executableEntries] get owner-exec permissions; everything else is left readable
     * but non-executable. Throws if [classpathPrefix] resolves to nothing, or if any
     * entry in [executableEntries] was not present in the unpacked tree.
     *
     * Needed for simulator-server, which ships a binary plus a sibling `resources/` tree
     * (screen-sharing-agent.jar + per-ABI .so files) that must live next to the binary.
     */
    fun unpackTree(
        classpathPrefix: String,
        targetDir: File,
        executableEntries: Set<String> = emptySet(),
    ) {
        val prefix = classpathPrefix.trimEnd('/')
        val classLoader = Unpacker::class.java.classLoader
        val rootUrl = classLoader.getResource(prefix)
            ?: classLoader.getResource("$prefix/")
            ?: error("No classpath resource tree found at '$classpathPrefix' — packaging bug?")
        if (!targetDir.exists()) targetDir.mkdirs()

        // Mount the jar (if any) as an NIO FileSystem so disk and jar paths share one walker.
        val unpackedExecutables = mutableSetOf<String>()
        openResourceRoot(rootUrl, prefix).use { root ->
            Files.walk(root.path).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { src ->
                    val rel = root.path.relativize(src).joinToString("/")
                    val target = File(targetDir, rel)
                    if (rel in executableEntries) unpackedExecutables += rel
                    if (target.exists() && Files.newInputStream(src).use { sameContents(it, target) }) {
                        return@forEach
                    }
                    target.parentFile?.mkdirs()
                    Files.copy(src, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    applyPermissions(target, isExecutable = rel in executableEntries)
                }
            }
        }
        val missing = executableEntries - unpackedExecutables
        check(missing.isEmpty()) {
            "Expected executable entries not found under '$classpathPrefix': $missing"
        }
    }

    private class ResourceRoot(val path: Path, private val onClose: () -> Unit) : AutoCloseable {
        override fun close() = onClose()
    }

    private fun openResourceRoot(rootUrl: URL, prefix: String): ResourceRoot {
        return when (rootUrl.protocol) {
            "jar" -> {
                // rootUrl: "jar:file:/.../maestro-cli.jar!/deps/simulator-server/darwin/"
                val jarUri = URI(rootUrl.toString().substringBefore("!/"))
                val (fs, owned) = openJarFileSystem(jarUri)
                ResourceRoot(fs.getPath("/", prefix)) { if (owned) fs.close() }
            }
            "file" -> ResourceRoot(Paths.get(rootUrl.toURI())) {}
            else -> error("Unsupported resource protocol for unpackTree: ${rootUrl.protocol}")
        }
    }

    private fun openJarFileSystem(jarUri: URI): Pair<FileSystem, Boolean> =
        try {
            FileSystems.newFileSystem(jarUri, emptyMap<String, Any>()) to true
        } catch (_: FileSystemAlreadyExistsException) {
            // Another caller already mounted this jar; reuse and don't close on our behalf.
            FileSystems.getFileSystem(jarUri) to false
        }

    private fun applyPermissions(file: File, isExecutable: Boolean) {
        if (isExecutable) grantBinaryPermissions(file) else grantDataPermissions(file)
    }

    private fun sameContents(resource: URL, target: File): Boolean {
        return DigestUtils.sha1Hex(resource.openStream()) == DigestUtils.sha1Hex(target.inputStream())
    }

    private fun sameContents(resource: InputStream, target: File): Boolean {
        return DigestUtils.sha1Hex(resource) == DigestUtils.sha1Hex(target.inputStream())
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

    private fun grantDataPermissions(file: File) {
        if (isPosixFilesystem()) {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
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