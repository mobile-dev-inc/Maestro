package maestro.debuglog

import com.google.common.truth.Truth.assertThat
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path

class ScopedLogCaptureTest {

    @TempDir
    lateinit var tempDir: Path

    private val ctx: LoggerContext get() = LogManager.getContext(false) as LoggerContext
    private var originalRootLevel: Level? = null

    @BeforeEach
    fun saveRootLevel() {
        originalRootLevel = ctx.configuration.rootLogger.level
    }

    @AfterEach
    fun restoreRootLevel() {
        originalRootLevel?.let {
            ctx.configuration.rootLogger.level = it
            ctx.updateLoggers()
        }
    }

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

    /**
     * Reproduces the save/restore race when two captures overlap (every
     * sharded run + every long-lived Worker / Studio JVM process). Doesn't
     * need real concurrency — sequential interleaved start/close has the same
     * shape:
     *
     *   t0  root.level = ERROR  (host baseline)
     *   t1  A starts: saves "previous=ERROR", sets root = ALL
     *   t2  B starts: saves "previous=ALL" (A's mutation), no-op on root
     *   t3  A closes: restores ERROR — *while B is still active*, so any
     *       INFO/DEBUG events from maestro.* loggers are now silently dropped
     *       at the logger-level gate before reaching B's appender
     *   t4  B closes: restores ALL (its stored "previous") — root JVM-wide
     *       logging level is now permanently corrupted to ALL
     *
     * Contract: after both captures close, root.level must equal what it was
     * before either started.
     */
    @Test
    fun `overlapping captures do not corrupt root logger level`() {
        // Force a known non-ALL baseline so the race is observable.
        // (CLI users would already have root at ALL via LogConfig.configure;
        // Worker / Studio embed-host JVMs do not.)
        ctx.configuration.rootLogger.level = Level.ERROR
        ctx.updateLoggers()

        val a = ScopedLogCapture.start(tempDir.resolve("a.log").toFile())
        val b = ScopedLogCapture.start(tempDir.resolve("b.log").toFile())
        a.close()
        b.close()

        assertThat(ctx.configuration.rootLogger.level).isEqualTo(Level.ERROR)
    }
}
