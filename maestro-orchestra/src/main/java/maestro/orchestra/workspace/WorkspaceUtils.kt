package maestro.orchestra.workspace

import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.streams.toList

object WorkspaceUtils {

    /**
     * Builds a workspace zip for cloud upload. The resulting zip has exactly one
     * workspace configuration, always at `/config.yaml`:
     *
     *   - If [configOverride] is non-null, its bytes are injected as `/config.yaml`.
     *     The override may live outside [file] entirely (e.g. `--config=/some/other/path.yaml`).
     *   - Else, if [file] is a directory and contains a root-level `config.yaml`/`config.yml`,
     *     its bytes are injected as `/config.yaml`.
     *   - Else, if [file] is a single flow file, a synthetic `flows: [<relative path>]`
     *     config is injected so the worker only runs the requested flow.
     *   - Else (directory upload with no config anywhere), no `/config.yaml` is written.
     *
     * Workspace-config-shaped YAMLs anywhere in [file] (detected by
     * [isWorkspaceConfigFile]) are always stripped from the zip contents. This keeps
     * the invariant simple for the cloud side, where [maestro.orchestra.workspace.WorkspaceValidator]
     * hardcodes its lookup to `/config.yaml` / `/config.yml` at the zip root — that
     * validator relies on this builder to guarantee there is exactly one.
     */
    fun createWorkspaceZip(file: Path, out: Path, configOverride: Path? = null) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        if (configOverride != null && !configOverride.exists()) {
            throw FileNotFoundException(configOverride.absolutePathString())
        }

        val walkedFiles = if (!file.isDirectory()) {
            DependencyResolver.discoverAllDependencies(file)
        } else {
            Files.walk(file).filter { !it.isDirectory() }.toList()
        }

        // The cloud validator assumes exactly one workspace config at the zip root.
        // Strip every workspace-config-shaped YAML from the walk so we can inject a
        // single canonical /config.yaml below without ever producing a duplicate.
        val filesToInclude = walkedFiles.filter { !isWorkspaceConfigYaml(it) }

        val relativeTo = if (file.isDirectory()) file else findCommonAncestor(filesToInclude)
        createWorkspaceZipFromFiles(filesToInclude, relativeTo, out)

        val injectedConfigContent: String? = when {
            // --config=<path>: caller explicitly picked a config; use it verbatim,
            // regardless of whether it lives inside or outside the workspace.
            configOverride != null -> configOverride.readText()

            // Directory upload: preserve the workspace's own root config.yaml / config.yml
            // (or skip injection if the workspace doesn't define one).
            file.isDirectory() -> findRootConfigFile(file)?.readText()

            // Single-file upload: synthesize a config that restricts execution to just this
            // flow. Without this, sibling flows pulled in via the dependency resolver would
            // also be executed because the common ancestor sits above the flow's directory.
            else -> syntheticSingleFlowConfig(file, relativeTo)
        }

        if (injectedConfigContent != null) {
            injectConfigYamlContent(out, injectedConfigContent)
        }
    }

    private fun findRootConfigFile(workspaceDir: Path): Path? {
        return workspaceDir.resolve("config.yaml").takeIf { it.exists() }
            ?: workspaceDir.resolve("config.yml").takeIf { it.exists() }
    }

    private fun syntheticSingleFlowConfig(flowFile: Path, relativeTo: Path): String {
        val flowRelativePath = relativeTo.relativize(normalizePath(flowFile)).toString()
        return "flows:\n  - \"$flowRelativePath\"\n"
    }

    private fun isWorkspaceConfigYaml(path: Path): Boolean {
        val ext = path.extension
        if (ext != "yaml" && ext != "yml") return false
        return isWorkspaceConfigFile(path)
    }

    private fun injectConfigYamlContent(zipPath: Path, content: String) {
        val zipUri = URI.create("jar:${zipPath.toUri()}")
        FileSystems.newFileSystem(zipUri, mapOf("create" to "false")).use { fs ->
            val configEntry = fs.getPath("config.yaml")
            Files.writeString(configEntry, content)
        }
    }

    private fun normalizePath(path: Path): Path {
        return try {
            path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            path.toAbsolutePath().normalize()
        }
    }

    internal fun findCommonAncestor(paths: List<Path>): Path {
        if (paths.isEmpty()) throw IllegalArgumentException("paths must not be empty")
        if (paths.size == 1) return normalizePath(paths.first()).parent

        val normalizedPaths = paths.map { normalizePath(it) }
        var ancestor = normalizedPaths.first().parent

        for (path in normalizedPaths.drop(1)) {
            ancestor = commonPrefix(ancestor, path.parent)
        }

        return ancestor
    }

    private fun commonPrefix(a: Path, b: Path): Path {
        val aRoot = a.root ?: throw IllegalArgumentException("Path must be absolute: $a")
        val bRoot = b.root ?: throw IllegalArgumentException("Path must be absolute: $b")
        if (aRoot != bRoot) throw IllegalArgumentException("Paths have different roots: $a and $b")

        val aParts = (0 until a.nameCount).map { a.getName(it) }
        val bParts = (0 until b.nameCount).map { b.getName(it) }

        var commonCount = 0
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            if (aParts[i] == bParts[i]) commonCount++ else break
        }

        return if (commonCount == 0) aRoot else aRoot.resolve(aParts.subList(0, commonCount).joinToString(a.fileSystem.separator))
    }

    fun createWorkspaceZipFromFiles(files: List<Path>, relativeTo: Path, out: Path) {
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())

        val outUri = URI.create("jar:${out.toUri()}")
        FileSystems.newFileSystem(outUri, mapOf("create" to "true")).use { fs ->
            files.forEach {
                val outPath = fs.getPath(relativeTo.relativize(it).toString())
                if (outPath.parent != null) {
                    Files.createDirectories(outPath.parent)
                }
                it.copyTo(outPath)
            }
        }
    }
}
