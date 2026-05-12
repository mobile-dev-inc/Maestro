package maestro.debuglog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path

class ScopedLogCaptureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `captures maestro logger lines and excludes non-maestro lines`() {
        val logFile = tempDir.resolve("captured.log").toFile()
        val capture = ScopedLogCapture.start(logFile)

        val maestroLogger = LoggerFactory.getLogger("maestro.test.example")
        val otherLogger = LoggerFactory.getLogger("io.netty.example")
        val flatLogger = LoggerFactory.getLogger("MAESTRO")

        maestroLogger.info("hello maestro")
        otherLogger.info("noise from netty")
        flatLogger.info("hello flat")

        capture.close()

        assertThat(logFile.exists()).isTrue()
        val content = logFile.readText()
        assertThat(content).contains("hello maestro")
        assertThat(content).contains("hello flat")
        assertThat(content).doesNotContain("noise from netty")
    }

    @Test
    fun `close is idempotent`() {
        val logFile = tempDir.resolve("captured.log").toFile()
        val capture = ScopedLogCapture.start(logFile)

        capture.close()
        capture.close()  // second call must not throw
    }

    @Test
    fun `appender is detached on close so later logs do not land in file`() {
        val logFile = tempDir.resolve("captured.log").toFile()
        val capture = ScopedLogCapture.start(logFile)
        val maestroLogger = LoggerFactory.getLogger("maestro.test.detach")

        maestroLogger.info("before close")
        capture.close()

        val sizeAfterClose = logFile.length()
        maestroLogger.info("after close should not appear")

        // File size should be unchanged after close (appender detached).
        assertThat(logFile.length()).isEqualTo(sizeAfterClose)
    }
}
