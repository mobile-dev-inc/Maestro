package maestro.web.record

import okio.Sink
import org.openqa.selenium.WebDriver
import org.openqa.selenium.devtools.DevTools
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.v147.page.Page
import org.openqa.selenium.devtools.v147.page.model.ScreencastFrame
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

    private lateinit var devTools: DevTools
    private lateinit var recordingExecutor: ExecutorService

    /** Stops the screencast that is currently running, if any. */
    private var activeScreencast: AutoCloseable? = null

    /** Read on the DevTools event thread: once set, this recorder's frame listener does nothing. */
    @Volatile
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
        devTools = requireDevTools().devTools

        recordingExecutor = Executors.newSingleThreadExecutor()
        videoEncoder.start(out)
        val startedAt = clock.instant()
        this.startedAt = startedAt

        try {
            listenForFrames()
            startScreencast()
        } catch (e: Throwable) {
            // Release whatever the start got to: the screencast, the listener, the encoder's output.
            closed = true
            runCatching { stopAndFinish(endMs = { 0 }) }
            throw e
        }
        return startedAt
    }

    /** A new window needs its own screencast; the listener registered at start already covers it. */
    fun onWindowChange() {
        if (closed) {
            return
        }

        startScreencast()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true

        // The video ends when the browser stops capturing, not when the encode backlog drains.
        stopAndFinish(endMs = { elapsedMs() })
    }

    /**
     * Registered once per recorder. Selenium keeps listeners for the whole DevTools connection, so
     * one added per screencast would see every frame again after each window change.
     */
    private fun listenForFrames() {
        devTools.addListener(Page.screencastFrame()) { frame ->
            // Listeners cannot be removed one by one; a closed recorder's listener stays inert, and
            // leaves acknowledging to whichever recorder is running now.
            if (closed) return@addListener
            // Stamped on arrival, before the encode queue, so a backlog cannot shift the frame.
            val arrivedAtMs = elapsedMs()
            recordingExecutor.submit { encodeAndAcknowledge(frame, arrivedAtMs) }
        }
    }

    private fun encodeAndAcknowledge(frame: ScreencastFrame, arrivedAtMs: Long) {
        try {
            val imageBytes = Base64.getDecoder().decode(frame.data)
            videoEncoder.encodeFrame(imageBytes, atMs = arrivedAtMs)
        } catch (e: Throwable) {
            if (encodeFailure == null) encodeFailure = e
            failedFrames++
        } finally {
            // Chrome stops sending frames once too many go unacknowledged.
            devTools.send(Page.screencastFrameAck(frame.sessionId))
        }
    }

    private fun startScreencast() {
        stopScreencast()

        devTools.createSessionIfThereIsNotOne()
        devTools.send(Page.enable(Optional.of(false)))
        // Recorded before the request: one that fails midway (a reply that times out) may still
        // have started the screencast, and stopping one that never started is harmless.
        activeScreencast = AutoCloseable { devTools.send(Page.stopScreencast()) }
        devTools.send(
            Page.startScreencast(
                Optional.of(Page.StartScreencastFormat.JPEG),
                Optional.of(80),
                Optional.of(1280),
                Optional.of(1280),
                Optional.of(1)
            )
        )
    }

    private fun stopScreencast() {
        val screencast = activeScreencast ?: return
        activeScreencast = null
        screencast.close()
    }

    /**
     * Stops the screencast, then lets the queued frames encode and finishes the video at [endMs],
     * taken once the screencast has stopped. Even if stopping it fails, the encoder still releases
     * the output sink.
     */
    private fun stopAndFinish(endMs: () -> Long) {
        try {
            stopScreencast()
        } finally {
            val videoEndMs = endMs()
            recordingExecutor.shutdown()
            recordingExecutor.awaitTermination(2, TimeUnit.MINUTES)
            videoEncoder.finish(endMs = videoEndMs)
        }
    }

    private fun elapsedMs(): Long =
        clock.millis() - checkNotNull(startedAt) { "Screen recording has not been started" }.toEpochMilli()

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
