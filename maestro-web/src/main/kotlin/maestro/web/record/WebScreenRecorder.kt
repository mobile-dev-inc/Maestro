package maestro.web.record

import okio.Sink
import org.openqa.selenium.WebDriver
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.v144.page.Page
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebScreenRecorder(
    private val videoEncoder: VideoEncoder,
    private val seleniumDriver: WebDriver
) : AutoCloseable {

    private val screenRecordingSessions = mutableListOf<AutoCloseable>()
    private lateinit var recordingExecutor: ExecutorService

    private var closed = false

    fun startScreenRecording(out: Sink) {
        ensureNotClosed()

        recordingExecutor = Executors.newSingleThreadExecutor()
        videoEncoder.start(out)

        startScreenRecordingForCurrentWindow()
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

        closeScreenRecordingSessions()

        recordingExecutor.shutdown()
        recordingExecutor.awaitTermination(2, TimeUnit.MINUTES)

        videoEncoder.close()
    }

    private fun startScreenRecordingForCurrentWindow() {
        closeScreenRecordingSessions()

        val driver = seleniumDriver as HasDevTools

        val seleniumDevTools = driver.devTools

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
            recordingExecutor.submit {
                val imageData = frame.data
                val imageBytes = Base64.getDecoder().decode(imageData)

                videoEncoder.encodeFrame(imageBytes)

                seleniumDevTools.send(Page.screencastFrameAck(frame.sessionId))
            }
        }

        val session = AutoCloseable { seleniumDevTools.send(Page.stopScreencast()) }
        screenRecordingSessions.add(session)
    }

    private fun closeScreenRecordingSessions() {
        screenRecordingSessions.forEach {
            it.close()
        }
        screenRecordingSessions.clear()
    }

    private fun ensureNotClosed() {
        if (closed) {
            error("Screen recorder is already closed")
        }
    }

}