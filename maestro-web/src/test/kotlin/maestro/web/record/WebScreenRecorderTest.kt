package maestro.web.record

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.openqa.selenium.WebDriver
import org.openqa.selenium.devtools.DevTools
import org.openqa.selenium.devtools.Event
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.v147.page.model.ScreencastFrame
import org.openqa.selenium.devtools.v147.page.model.ScreencastFrameMetadata
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.function.Consumer
import org.junit.jupiter.api.assertThrows
import org.openqa.selenium.devtools.Command

class WebScreenRecorderTest {

    private class RecordingEncoder : VideoEncoder {
        val frameTimesMs = mutableListOf<Long>()
        var finishedAtMs: Long? = null
        var onEncode: () -> Unit = {}

        override fun start(out: Sink) = Unit
        override fun encodeFrame(frame: ByteArray, atMs: Long) { onEncode(); frameTimesMs += atMs }
        override fun finish(endMs: Long) { finishedAtMs = endMs }
    }

    private class SteppingClock(private var nowMs: Long) : Clock() {
        fun advance(ms: Long) { nowMs += ms }
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val devTools = mockk<DevTools>(relaxed = true)
    private val driver = mockk<WebDriver>(moreInterfaces = arrayOf(HasDevTools::class)).also {
        every { (it as HasDevTools).devTools } returns devTools
    }
    private val encoder = RecordingEncoder()
    private val clock = SteppingClock(START_MS)
    private val recorder = WebScreenRecorder(encoder, driver, clock)

    @Test
    fun `the video ends when the screencast stops, not after the encode backlog drains`() {
        val onFrame = slot<Consumer<ScreencastFrame>>()
        every { devTools.addListener(any<Event<ScreencastFrame>>(), capture(onFrame)) } just Runs
        val encodeGate = CountDownLatch(1)
        encoder.onEncode = { encodeGate.await() }

        recorder.startScreenRecording(Buffer())
        clock.advance(1_000)
        onFrame.captured.accept(screencastFrame()) // queued behind the gate
        clock.advance(1_500)
        val closer = Thread { recorder.close() }.also { it.start() }
        while (closer.state != Thread.State.TIMED_WAITING) Thread.sleep(1) // close() is now draining the queue
        clock.advance(5_000) // the drain takes a while
        encodeGate.countDown()
        closer.join()

        assertThat(encoder.finishedAtMs).isEqualTo(2_500L)
    }

    @Test
    fun `a frame that fails to encode is acknowledged and reported, not thrown`() {
        val onFrame = slot<Consumer<ScreencastFrame>>()
        every { devTools.addListener(any<Event<ScreencastFrame>>(), capture(onFrame)) } just Runs
        var first = true
        encoder.onEncode = { if (first) { first = false; throw IllegalStateException("bad frame") } }

        recorder.startScreenRecording(Buffer())
        onFrame.captured.accept(screencastFrame())
        onFrame.captured.accept(screencastFrame())
        recorder.close() // one bad frame must not discard an otherwise complete recording

        assertThat(recorder.encodeFailure).hasMessageThat().isEqualTo("bad frame")
        assertThat(recorder.failedFrames).isEqualTo(1)
        // Without the ack Chrome stops sending frames, so the recording would silently freeze instead.
        verify(exactly = 2) { devTools.send(match<Command<*>> { it.method == "Page.screencastFrameAck" }) }
    }

    @Test
    fun `a screencast that fails to start releases the encoder`() {
        every { devTools.send(match<Command<*>> { it.method == "Page.startScreencast" }) } throws IllegalStateException("no page")

        assertThrows<IllegalStateException> { recorder.startScreenRecording(Buffer()) }

        assertThat(encoder.finishedAtMs).isNotNull()
    }

    @Test
    fun `frames are placed at their arrival time since the reported start and the video runs until close`() {
        val onFrame = slot<Consumer<ScreencastFrame>>()
        every { devTools.addListener(any<Event<ScreencastFrame>>(), capture(onFrame)) } just Runs

        val startedAt = recorder.startScreenRecording(Buffer())
        clock.advance(1_500)
        onFrame.captured.accept(screencastFrame())
        clock.advance(2_500)
        recorder.close()

        assertThat(startedAt).isEqualTo(Instant.ofEpochMilli(START_MS))
        assertThat(encoder.frameTimesMs).containsExactly(1_500L)
        assertThat(encoder.finishedAtMs).isEqualTo(4_000L)
    }

    private fun screencastFrame() = ScreencastFrame(
        Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
        ScreencastFrameMetadata(0, 1, 64, 64, 0, 0, Optional.empty()),
        1,
    )

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
