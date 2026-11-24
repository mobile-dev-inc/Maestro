package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.error.SyntaxProblem
import maestro.orchestra.yaml.YamlCommandReader
import picocli.CommandLine
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "check-syntax",
    description = [
        "Check the syntax of Maestro flow files and report every problem found",
        "It does not check the JavaScript inside \${...} or whether a command is supported on a given platform.",
    ],
)
class CheckSyntaxCommand : Callable<Int> {

    @CommandLine.Parameters(
        arity = "1..*",
        paramLabel = "<file>",
        description = ["Maestro flow files to check, or \"-\" for stdin"],
    )
    private lateinit var files: List<String>

    @CommandLine.Option(
        names = ["--format"],
        description = ["Output format: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})"],
    )
    private var format: Format = Format.TEXT

    internal enum class Format { TEXT, JSON }

    internal var stdin: () -> String = { System.`in`.readBytes().toString(Charsets.UTF_8) }
    internal var workingDir: Path = Paths.get("").toAbsolutePath()
    internal var checkFlow: (text: String, path: Path, snippet: Boolean) -> List<SyntaxProblem> =
        { text, path, snippet -> YamlCommandReader.checkSyntaxReportingAllProblems(text, path, snippet) }

    /**
     * What checking one file found: [ioError] when it could not be read, otherwise its [problems]. [referenced]
     * when it isn't an argument but a file an argument runs; for an argument, [affectedBy] lists the referenced
     * files whose findings stop it loading.
     */
    internal data class FileResult(
        val path: String,
        val ioError: String?,
        val problems: List<SyntaxProblem>,
        val referenced: Boolean = false,
        val affectedBy: List<String> = emptyList(),
    )

    override fun call(): Int {
        if ("-" in files && files.size > 1) {
            System.err.println("\"-\" (stdin) can't be combined with files")
            return 2
        }
        val results = check()
        val arguments = results.filterNot { it.referenced }
        val fileCount = arguments.size
        // An argument is "with errors" when it wouldn't load: a finding of its own or in a file it runs.
        val withErrors = arguments.count { it.ioError == null && (it.problems.isNotEmpty() || it.affectedBy.isNotEmpty()) }
        // Arguments, and referenced files whose own check failed internally.
        val notRead = results.count { it.ioError != null }
        val errors = results.sumOf { it.problems.size }
        val exitCode = when {
            notRead > 0 -> 2
            errors > 0 || withErrors > 0 -> 1
            else -> 0
        }

        if (format == Format.JSON) {
            println(renderJson(results, fileCount, withErrors, errors, notRead))
            return exitCode
        }

        for (result in results) {
            if (result.ioError != null) {
                println("${result.path}: error: ${result.ioError}")
                continue
            }
            if (result.problems.isEmpty() && result.affectedBy.isNotEmpty()) {
                println("${result.path}: affected by problems in ${result.affectedBy.joinToString(", ")}")
                continue
            }
            result.problems.take(MAX_PER_FILE).forEach { problem ->
                println("${result.path}:${problem.line}:${problem.column}: error: ${problem.title} [${problem.code}]")
                println(problem.renderDetail())
                println()
            }
            val more = result.problems.size - MAX_PER_FILE
            if (more > 0) {
                println("... and $more more")
                println()
            }
        }

        println(
            "Checked $fileCount ${if (fileCount == 1) "file" else "files"}: $withErrors with errors, " +
                "$errors ${if (errors == 1) "error" else "errors"}" +
                (if (notRead > 0) ", $notRead could not be read" else "") + "."
        )

        return exitCode
    }

    private fun renderJson(results: List<FileResult>, fileCount: Int, withErrors: Int, errors: Int, notRead: Int): String {
        val document = linkedMapOf(
            "files" to results.map { result ->
                linkedMapOf<String, Any?>(
                    "path" to result.path,
                    "error" to result.ioError,
                    "referenced" to result.referenced,
                ).apply {
                    if (!result.referenced) put("affectedBy", result.affectedBy)
                } + mapOf(
                    "findings" to result.problems.map { problem ->
                        linkedMapOf(
                            "line" to problem.line,
                            "column" to problem.column,
                            "endLine" to problem.endLine,
                            "endColumn" to problem.endColumn,
                            "code" to problem.code,
                            "title" to problem.title,
                            "message" to problem.message,
                            "accepted" to problem.accepted,
                            "suggestions" to problem.suggestions,
                            "docs" to problem.docs,
                        )
                    },
                )
            },
            "summary" to linkedMapOf(
                "files" to fileCount,
                "filesWithErrors" to withErrors,
                "errors" to errors,
                "filesNotRead" to notRead,
            ),
        )
        return jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(document)
    }

    /**
     * One result per distinct argument, then one per referenced file that is not itself an argument and has
     * findings. Every file is checked on its own, once: a referenced file's findings come from its own check,
     * not from the first one its parent's load ran into.
     */
    internal fun check(): List<FileResult> {
        val arguments = files.distinctBy { key(it) }
        val argumentPaths = arguments.filter { it != "-" }.map { key(it) }.toSet()

        // Per real path: the findings a file's own check found in it, and those other files' checks found in it.
        val own = HashMap<Path, List<SyntaxProblem>>()
        val surfaced = LinkedHashMap<Path, MutableList<SyntaxProblem>>()
        // Referenced files that are not arguments, each queued once, with the path their parent gave for them.
        val referenced = LinkedHashMap<Path, Path>()
        val queue = ArrayDeque<Path>()
        // Per checked file: the other files its check surfaced findings in, directly or through other files.
        val reaches = HashMap<Path, Set<Path>>()

        fun record(realPath: Path, checked: List<SyntaxProblem>) {
            val (mine, others) = checked.partition { real(it.file) == realPath }
            own[realPath] = mine
            reaches[realPath] = others.mapTo(LinkedHashSet()) { real(it.file) }
            for (problem in others) {
                val problemReal = real(problem.file)
                surfaced.getOrPut(problemReal) { ArrayList() } += problem
                if (problemReal !in argumentPaths && problemReal !in referenced) {
                    referenced[problemReal] = problem.file
                    queue.addLast(problemReal)
                }
            }
        }

        val argumentResults = arguments.map { arg ->
            if (arg == "-") {
                val stdinPath = workingDir.resolve("stdin").toAbsolutePath().normalize()
                // A blank snippet has no config to be missing, so the parser's "Config Section Required" would mislead.
                val text = stdin()
                val error = if (text.isBlank()) "input is empty" else checkOne(real(stdinPath), stdinPath, text, snippet = true, ::record)
                return@map Triple(arg, real(stdinPath), error)
            }
            val path = workingDir.resolve(arg).toAbsolutePath().normalize()
            val (text, readError) = read(path)
            val error = readError ?: checkOne(real(path), path, text!!, snippet = false, ::record)
            Triple(arg, real(path), error)
        }

        // A referenced file whose own check fails is reported with that internal error, as an argument is.
        val internalErrors = HashMap<Path, String>()
        while (queue.isNotEmpty()) {
            val realPath = queue.removeFirst()
            val path = referenced.getValue(realPath)
            // A referenced file that can't be read keeps the findings its parent surfaced.
            val text = read(path).first ?: continue
            checkOne(realPath, path, text, snippet = false, ::record)?.let { internalErrors[realPath] = it }
        }

        // A file's own check is preferred; when it found nothing in the file (a cycle can put the finding in the
        // other file of the pair), the findings other checks surfaced in it are used instead, so nothing is lost.
        fun findings(realPath: Path): List<SyntaxProblem> =
            (own[realPath]?.takeIf { it.isNotEmpty() } ?: surfaced[realPath].orEmpty())
                .distinctBy { listOf(it.line, it.column, it.code) }

        val argumentNames = argumentResults.associate { (arg, realPath, _) -> realPath to arg }
        fun shown(realPath: Path) = argumentNames[realPath] ?: display(referenced.getValue(realPath))

        val results = argumentResults.map { (arg, realPath, error) ->
            val name = if (arg == "-") "<stdin>" else arg
            if (error != null) return@map FileResult(name, error, emptyList())
            val affectedBy = reaches[realPath].orEmpty().filter { findings(it).isNotEmpty() }.map(::shown)
            FileResult(name, null, findings(realPath), affectedBy = affectedBy)
        }
        val referencedResults = referenced.mapNotNull { (realPath, path) ->
            internalErrors[realPath]?.let { return@mapNotNull FileResult(display(path), it, emptyList(), referenced = true) }
            findings(realPath).takeIf { it.isNotEmpty() }?.let { FileResult(display(path), null, it, referenced = true) }
        }
        return results + referencedResults
    }

    /** Checks one file and records its findings; returns an internal error instead of throwing. */
    private fun checkOne(
        realPath: Path,
        path: Path,
        text: String,
        snippet: Boolean,
        record: (Path, List<SyntaxProblem>) -> Unit,
    ): String? {
        val checked = try {
            checkFlow(text, path, snippet)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // A bug in the check for this file mustn't take the other files' results with it.
            return internalError(e)
        }
        record(realPath, checked)
        return null
    }

    /** The file's text, or why it couldn't be read. */
    private fun read(path: Path): Pair<String?, String?> = try {
        when {
            !Files.exists(path) -> null to "file does not exist"
            Files.isDirectory(path) -> null to "is a directory"
            else -> String(Files.readAllBytes(path), Charsets.UTF_8) to null
        }
    } catch (e: AccessDeniedException) {
        null to "permission denied"
    } catch (e: Exception) {
        // An IO exception's message is often just the path, which says nothing about why.
        null to "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun internalError(e: Throwable) = "internal error: ${e.javaClass.simpleName}: ${e.message}"

    private fun key(arg: String): Any =
        if (arg == "-") arg else real(workingDir.resolve(arg).toAbsolutePath().normalize())

    private fun real(path: Path): Path =
        try { path.toRealPath() } catch (e: Exception) { path.toAbsolutePath().normalize() }

    private fun display(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        return if (absolute.startsWith(workingDir.normalize())) workingDir.normalize().relativize(absolute).toString()
        else absolute.toString()
    }

    private companion object {
        const val MAX_PER_FILE = 20
    }
}
