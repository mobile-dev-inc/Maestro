package util

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory

object CommandLineUtils {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val nullFile = File(if (isWindows) "NUL" else "/dev/null")
    private val logger = LoggerFactory.getLogger(CommandLineUtils::class.java)

    @Suppress("SpreadOperator")
    fun runCommand(
            parts: List<String>,
            waitForCompletion: Boolean = true,
            outputFile: File? = null,
            params: Map<String, String> = emptyMap()
    ): Process {
        logger.info("Running command line operation: $parts with $params")

        val processBuilder =
                if (outputFile != null) {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(outputFile)
                            .redirectError(outputFile)
                } else {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(nullFile)
                            .redirectError(ProcessBuilder.Redirect.PIPE)
                }

        processBuilder.environment().putAll(params)
        val process = processBuilder.start()

        if (waitForCompletion) {
            val finished = try {
                process.waitFor(5, TimeUnit.MINUTES)
            } catch (e: InterruptedException) {
                terminate(process)
                Thread.currentThread().interrupt()
                throw e
            }
            if (!finished) {
                terminate(process)
                throw TimeoutException("Command timed out after 5 minutes: ${parts.joinToString(" ")}")
            }

            if (process.exitValue() != 0) {
                val processOutput = process.errorStream.source().buffer().readUtf8()

                logger.error("Process failed with exit code ${process.exitValue()}")
                logger.error("Error output $processOutput")

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }

    private fun terminate(process: Process) {
        var interrupted = false
        try {
            if (!process.isAlive) return
            process.destroy()
            val stopped = try {
                process.waitFor(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!stopped && process.isAlive) {
                process.destroyForcibly()
                try {
                    process.waitFor(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
