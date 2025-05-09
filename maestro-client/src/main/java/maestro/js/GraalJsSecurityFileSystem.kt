package maestro.js

import org.graalvm.polyglot.io.FileSystem
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.LinkOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.DirectoryStream
import java.nio.file.OpenOption
import java.nio.file.AccessMode
import java.nio.channels.SeekableByteChannel
import java.net.URI
import java.io.IOException

/**
 * A secure file system implementation for GraalJS that restricts access to a specific module directory.
 * This prevents scripts from accessing files outside their allowed scope.
 */
class GraalJsSecurityFileSystem(moduleDir: String) : FileSystem {
    private val defaultFileSystem = FileSystem.newDefaultFileSystem()
    private val moduleBasePath = Paths.get(moduleDir).toAbsolutePath().normalize()


    private fun isPathAllowed(path: Path): Boolean {
        val normalizedPath = path.normalize()
        return normalizedPath.startsWith(moduleBasePath)
    }

    private fun isPathAllowed(path: String): Boolean {
        return isPathAllowed(Paths.get(path))
    }

    override fun parsePath(uri: URI): Path {
        return defaultFileSystem.parsePath(uri)
    }

    override fun parsePath(path: String): Path {
        return defaultFileSystem.parsePath(path)
    }

    override fun checkAccess(path: Path, modes: Set<AccessMode>?, vararg linkOptions: LinkOption?) {
        if (!isPathAllowed(path)) {
            throw SecurityException("Access to path outside module directory is not allowed: $path")
        }
        defaultFileSystem.checkAccess(path, modes, *linkOptions)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>?) {
        if (!isPathAllowed(dir)) {
            throw SecurityException("Cannot create directory outside module directory: $dir")
        }
        defaultFileSystem.createDirectory(dir, *attrs)
    }

    override fun delete(path: Path) {
        if (!isPathAllowed(path)) {
            throw SecurityException("Cannot delete file outside module directory: $path")
        }
        defaultFileSystem.delete(path)
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>?,
        vararg attrs: FileAttribute<*>?
    ): SeekableByteChannel {
        if (!isPathAllowed(path)) {
            throw SecurityException("Cannot access file outside module directory: $path")
        }
        return defaultFileSystem.newByteChannel(path, options, *attrs)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>?
    ): DirectoryStream<Path> {
        if (!isPathAllowed(dir)) {
            throw SecurityException("Cannot list directory outside module directory: $dir")
        }
        return defaultFileSystem.newDirectoryStream(dir, filter)
    }

    override fun toAbsolutePath(path: Path): Path {
        return defaultFileSystem.toAbsolutePath(path)
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption?): Path {
        val realPath = defaultFileSystem.toRealPath(path, *linkOptions)
        if (!isPathAllowed(realPath)) {
            throw SecurityException("Cannot access path outside module directory: $realPath")
        }
        return realPath
    }

    override fun readAttributes(
        path: Path,
        attributes: String?,
        vararg options: LinkOption?
    ): MutableMap<String, Any> {
        if (!isPathAllowed(path)) {
            throw SecurityException("Cannot read attributes of file outside module directory: $path")
        }
        return defaultFileSystem.readAttributes(path, attributes, *options)
    }
} 