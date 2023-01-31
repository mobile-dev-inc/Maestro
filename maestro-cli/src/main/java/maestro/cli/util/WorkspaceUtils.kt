package maestro.cli.util

import maestro.cli.CliError
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.yaml.YamlCommandReader
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.streams.toList

object WorkspaceUtils {

    fun createWorkspaceZip(file: Path, out: Path, includeTags: List<String>, excludeTags: List<String>) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        val files = Files.walk(file).filter { !it.isDirectory() }.toList()
        val relativeTo = if (file.isDirectory()) file else file.parent

        val flowsMatchingTagsRule = filterFilesBasedOnTags(
            files,
            includeTags = includeTags,
            excludeTags = excludeTags,
        )

        if (flowsMatchingTagsRule.isEmpty()) {
            throw CliError("No flow returned from the tag filter used")
        }

        val outUri = URI.create("jar:file:${out.toAbsolutePath()}")
        FileSystems.newFileSystem(outUri, mapOf("create" to "true")).use { fs ->
            flowsMatchingTagsRule.forEach {
                val outPath = fs.getPath(relativeTo.relativize(it).toString())
                if (outPath.parent != null) {
                    Files.createDirectories(outPath.parent)
                }
                it.copyTo(outPath)
            }
        }
    }

    fun createMaestroMockServerWorkspaceZip(file: Path, out: Path) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        val files = Files.walk(file).filter { !it.isDirectory() && it.name.endsWith(".js") }.toList()
        val relativeTo = if (file.isDirectory()) file else file.parent

        if (files.isEmpty()) {
            throw CliError("No Javascript files with mock rules found in workspace: ${file.absolutePathString()}")
        }

        if (files.none { it.name == "index.js" }) {
            throw CliError("No index.js file found in workspace: ${file.absolutePathString()}")
        }

        val outUri = URI.create("jar:file:${out.toAbsolutePath()}")
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

    fun isFlowFile(path: Path): Boolean {
        return with(path.toFile()) {
            isFile
                && extension in setOf("yaml", "yml")
                && nameWithoutExtension != "config"
        }
    }

    fun filterFilesBasedOnTags(
        files: List<Path>,
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
    ): List<Path> {

        val flowFiles = files
            .filter(::isFlowFile)

        val flowsMatchingTagRule = mutableSetOf<Path>()
        val referencedSubFlows = mutableSetOf<String>()

        flowFiles.forEach {
            val commands = YamlCommandReader.readCommands(it)
            val config = YamlCommandReader.getConfig(commands)
            val tags = config?.tags ?: emptyList()

            if (excludeTags.isNotEmpty() && tags.any(excludeTags::contains)) {
                return@forEach
            }

            if (includeTags.isNotEmpty() && !tags.any(includeTags::contains)) {
                return@forEach
            }

            flowsMatchingTagRule.add(it)

            referencedSubFlows += referencedSubFlows(commands)
        }

        flowFiles
            .filter { flowFile ->
                referencedSubFlows.any { flowFile.pathString.endsWith(it) }
            }
            .forEach {
                flowsMatchingTagRule.add(it)
            }

        files
            .filterNot(::isFlowFile)
            .forEach {
                flowsMatchingTagRule.add(it)
            }

        return flowsMatchingTagRule.toList()
    }

    private fun referencedSubFlows(commands: List<MaestroCommand>): List<String> {
        return commands
            .flatMap { referencedSubFlows(it) }
    }

    private fun referencedSubFlows(maestroCommand: MaestroCommand): List<String> {
        return when (val command = maestroCommand.asCommand()) {
            is RunFlowCommand -> command.sourceDescription?.let { listOf(it) } ?: emptyList()
            is CompositeCommand -> referencedSubFlows(command.subCommands())
            else -> emptyList()
        }
    }

}
