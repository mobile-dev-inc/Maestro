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
    fun runCommand(parts: List<String>, waitForCompletion: Boolean = true, outputFile: File? = null): Process {
        logger.info("Running command line operation: $parts")

        val process = if (outputFile != null) {
            ProcessBuilder(*parts.toTypedArray())
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        } else {
            ProcessBuilder(*parts.toTypedArray())
                .redirectOutput(nullFile)
                .redirectError(nullFile)
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

                logger.error("Process failed with exit code ${process.exitValue()}")
                logger.error(processOutput)

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }
}
