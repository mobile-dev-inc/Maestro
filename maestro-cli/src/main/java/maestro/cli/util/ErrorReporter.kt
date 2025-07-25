package maestro.cli.util

import maestro.cli.api.ApiClient
import picocli.CommandLine
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.Exception

object ErrorReporter {

    private val executor = Executors.newCachedThreadPool {
        Executors.defaultThreadFactory().newThread(it).apply { isDaemon = true }
    }

    fun report(exception: Exception, parseResult: CommandLine.ParseResult) {
        val args = parseResult.expandedArgs()
        val scrubbedArgs = args.mapIndexed { idx, arg ->
            if (idx > 0 && args[idx - 1] in listOf("-e", "--env")) {
                val (key, value) = arg.split("=", limit = 1)
                key + "=" + hashString(value)
            } else arg
        }

        val task = executor.submit {
            ApiClient(EnvUtils.BASE_API_URL).sendErrorReport(
                exception,
                scrubbedArgs.joinToString(" ")
            )
        }

        runCatching { task.get(1, TimeUnit.SECONDS) }
    }

    private fun hashString(input: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray())
            .fold(StringBuilder()) { sb, it ->
                sb.append("%02x".format(it))
            }.toString()
    }

}
