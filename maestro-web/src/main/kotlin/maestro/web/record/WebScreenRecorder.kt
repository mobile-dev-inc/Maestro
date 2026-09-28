package maestro.web.record

import okio.Sink
import org.openqa.selenium.WebDriver
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.v147.page.Page
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebScreenRecorder(
    private val videoEncoder: VideoEncoder,
    private val seleniumDriver: WebDriver,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val screenRecordingSessions = mutableListOf<AutoCloseable>()
    private lateinit var recordingExecutor: ExecutorService

    private var closed = false

    /** The video's 0:00: every frame is placed at its arrival offset from this instant (see [VideoEncoder]). */
    private var startedAt: Instant? = null

    /**
     * The first frame that failed to encode, and how many did. A failed frame is dropped and the
     * previous one stays on screen, so this is reported for the caller to log rather than thrown:
     * one bad frame must not discard an otherwise complete recording.
     */
    @Volatile
    var encodeFailure: Throwable? = null
        private set

    @Volatile
    var failedFrames: Int = 0
        private set

    /** Starts the screencast and returns the instant it was requested, which is the video's 0:00. */
    fun startScreenRecording(out: Sink): Instant {
        ensureNotClosed()

        // Must precede videoEncoder.start: opening the sink first leaves a 0-byte file behind.
        requireDevTools()

        recordingExecutor = Executors.newSingleThreadExecutor()
        videoEncoder.start(out)
        val startedAt = clock.instant()
        this.startedAt = startedAt

        try {
            startScreenRecordingForCurrentWindow()
        } catch (e: Throwable) {
            // The encoder already holds the output sink and a scratch file; release them.
            recordingExecutor.shutdown()
            runCatching { videoEncoder.finish(endMs = 0) }
            throw e
        }
        return startedAt
    }

    fun onWindowChange() {
        if (closed) {
            return
        }

        startScreenRecordingForCurrentWindow()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true

        try {
            closeScreenRecordingSessions()
        } finally {
            // The video ends when the browser stops capturing, not when the encode backlog drains.
            // Even if stopping the screencast failed, the encoder must release the output sink.
            val endMs = elapsedMs()
            recordingExecutor.shutdown()
            recordingExecutor.awaitTermination(2, TimeUnit.MINUTES)
            videoEncoder.finish(endMs = endMs)
        }
    }

    private fun startScreenRecordingForCurrentWindow() {
        closeScreenRecordingSessions()

        val seleniumDevTools = requireDevTools().devTools

        seleniumDevTools.createSessionIfThereIsNotOne()

        seleniumDevTools.send(Page.enable(Optional.of(false)))

        seleniumDevTools.send(
            Page.startScreencast(
                Optional.of(Page.StartScreencastFormat.JPEG),
                Optional.of(80),
                Optional.of(1280),
                Optional.of(1280),
                Optional.of(1)
            )
        )

        seleniumDevTools.addListener(Page.screencastFrame()) { frame ->
            // Stamped on arrival, before the encode queue, so a backlog cannot shift the frame.
            val arrivedAtMs = elapsedMs()
            recordingExecutor.submit {
                try {
                    val imageBytes = Base64.getDecoder().decode(frame.data)
                    videoEncoder.encodeFrame(imageBytes, atMs = arrivedAtMs)
                } catch (e: Throwable) {
                    if (encodeFailure == null) encodeFailure = e
                    failedFrames++
                } finally {
                    // Chrome stops sending frames once too many go unacknowledged.
                    seleniumDevTools.send(Page.screencastFrameAck(frame.sessionId))
                }
            }
        }

        val session = AutoCloseable { seleniumDevTools.send(Page.stopScreencast()) }
        screenRecordingSessions.add(session)
    }

    private fun elapsedMs(): Long =
        clock.millis() - checkNotNull(startedAt) { "Screen recording has not been started" }.toEpochMilli()

    private fun closeScreenRecordingSessions() {
        screenRecordingSessions.forEach {
            it.close()
        }
        screenRecordingSessions.clear()
    }

    private fun requireDevTools(): HasDevTools =
        seleniumDriver as? HasDevTools
            ?: throw UnsupportedOperationException(
                "Screen recording requires a DevTools-capable driver, but " +
                    "${seleniumDriver.javaClass.name} does not implement HasDevTools"
            )

    private fun ensureNotClosed() {
        if (closed) {
            error("Screen recorder is already closed")
        }
    }

}