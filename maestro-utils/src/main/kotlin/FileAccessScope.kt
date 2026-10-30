package maestro.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a file path that a flow refers to.
 *
 * A path is given as an [anchor] directory plus a [candidate] string that is
 * either absolute or relative to the anchor. [under] keeps the result within a
 * root directory; [everything] resolves it without a bound.
 */
sealed class FileAccessScope {

    /**
     * Resolves [candidate] (absolute, or relative to [anchor]) to a path.
     * Existence and file type are the caller's concern.
     */
    abstract fun resolve(anchor: Path, candidate: String): Path

    /** Like [resolve], but returns null instead of throwing when the path lands outside the scope. */
    fun resolveOrNull(anchor: Path, candidate: String): Path? =
        try {
            resolve(anchor, candidate)
        } catch (e: PathOutsideScope) {
            null
        }

    /** Resolves paths under [root]. A path that lands outside [root] is rejected. */
    private class Bounded(root: Path) : FileAccessScope() {
        // Canonicalized once. The workspace root may sit under a symlinked
        // directory (e.g. a temp dir under macOS's /var -> /private/var), so the
        // root is resolved to its real location before any comparison.
        private val realRoot: Path = root.toRealPath()

        override fun resolve(anchor: Path, candidate: String): Path {
            val requested = anchor.fileSystem.getPath(candidate)
            val combined = if (requested.isAbsolute) requested else anchor.resolve(requested)
            val real = canonicalize(combined.toAbsolutePath().normalize())
            if (real != realRoot && !real.startsWith(realRoot)) {
                throw PathOutsideScope(candidate, "Path resolves outside the root: $candidate")
            }
            return real
        }

        private fun canonicalize(path: Path): Path {
            // toRealPath() on a path that does not exist yet throws, so walk up to the
            // nearest existing ancestor, canonicalize that, and re-append the tail.
            var existing = path
            val tail = ArrayDeque<Path>()
            while (!Files.exists(existing)) {
                val name = existing.fileName ?: return path.normalize()
                tail.addFirst(name)
                existing = existing.parent ?: return path.normalize()
            }
            var real = existing.toRealPath()
            for (segment in tail) {
                real = real.resolve(segment)
            }
            return real.normalize()
        }
    }

    /** Resolves paths without a bound, relative to [anchor] or absolute as given. */
    object everything : FileAccessScope() {
        override fun resolve(anchor: Path, candidate: String): Path {
            val requested = anchor.fileSystem.getPath(candidate)
            return if (requested.isAbsolute) {
                requested
            } else {
                anchor.resolve(requested).toAbsolutePath().normalize()
            }
        }
    }

    companion object {
        /** A scope bounded to [root]. A path that lands outside [root] is rejected. */
        fun under(root: Path): FileAccessScope = Bounded(root)
    }
}

/** Raised when a resolved path falls outside its [FileAccessScope] root. */
class PathOutsideScope(val path: String, message: String) : RuntimeException(message)
