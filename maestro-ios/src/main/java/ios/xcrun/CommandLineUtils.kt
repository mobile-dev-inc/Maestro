package ios.xcrun

import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object CommandLineUtils {

    fun runCommand(command: String, waitForCompletion: Boolean = true, outputFile: File? = null): Process {
        LOGGER.info("Running command line operation: $command")

        val parts = command.split("\\s".toRegex())
            .map { it.trim() }

        return runCommand(parts, waitForCompletion, outputFile)
    }

    @Suppress("SpreadOperator")
    fun runCommand(parts: List<String>, waitForCompletion: Boolean = true, outputFile: File? = null): Process {
        LOGGER.info("Running command line operation: $parts")

        val process = if (outputFile != null) {
            ProcessBuilder(*parts.toTypedArray())
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        } else {
            ProcessBuilder(*parts.toTypedArray())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        }

        if (waitForCompletion) {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            if (process.exitValue() != 0) {
                val processOutput = process.errorStream
                    .source()
                    .buffer()
                    .readUtf8()

                LOGGER.error("Process failed with exit code ${process.exitValue()}")
                LOGGER.error(processOutput)

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }

    private val LOGGER = LoggerFactory.getLogger(CommandLineUtils::class.java)
}
