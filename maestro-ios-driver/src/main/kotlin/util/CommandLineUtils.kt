package util

import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object CommandLineUtils {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val nullFile = File(if (isWindows) "NUL" else "/dev/null")
    private val logger = LoggerFactory.getLogger(CommandLineUtils::class.java)

    @Suppress("SpreadOperator")
    fun runCommand(
        parts: List<String>, 
        waitForCompletion: Boolean = true, 
        outputFile: File? = null, 
        params: Map<String, String> = emptyMap(),
        captureOutput: Boolean = false
    ): Process {
        logger.info("Running command line operation: $parts")

        val processBuilder = when {
            outputFile != null -> {
                ProcessBuilder(*parts.toTypedArray())
                    .redirectOutput(outputFile)
                    .redirectError(outputFile)
            }
            captureOutput -> {
                ProcessBuilder(*parts.toTypedArray())
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                // Don't redirect stdout when capturing output
            }
            else -> {
                ProcessBuilder(*parts.toTypedArray())
                    .redirectOutput(nullFile)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
            }
        }

        processBuilder.environment().putAll(params)
        val process = processBuilder.start()

        if (waitForCompletion) {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            if (process.exitValue() != 0) {
                val processOutput = process.errorStream
                    .source()
                    .buffer()
                    .readUtf8()

                logger.error("Process failed with exit code ${process.exitValue()}")
                logger.error("Error output $processOutput")

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }
}
