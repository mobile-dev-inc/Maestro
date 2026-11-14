package maestro.cli.graphics

import maestro.cli.runner.resultview.Frame
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jcodec.api.FrameGrab
import org.jcodec.api.PictureWithMetadata
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Picture
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToLong

interface FrameRenderer {
    fun render(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: BufferedImage,
        text: String,
    ): BufferedImage
}

/**
 * Composes the device's screen recording and the terminal's output into one video. A frame is
 * written each time the screen or the terminal text changes, and shown until the next change, so
 * work and file size follow how much happens on screen rather than how long the flow runs.
 */
class LocalVideoRenderer(
    private val frameRenderer: FrameRenderer,
    private val outputFile: File,
    /** Changes closer together than one frame at this rate are merged, keeping the last. */
    private val maxFPS: Int,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) {

    init {
        require(maxFPS in 1..1000) { "maxFPS must be between 1 and 1000, was $maxFPS" }
    }

    /**
     * @param recordedForMs how long the host kept the device recording, from when it started to when it was
     * asked to stop. Null when unknown, in which case no recorder time limit is assumed.
     */
    fun render(
        screenRecording: File,
        textFrames: List<Frame>,
        recordedForMs: Long? = null,
    ) {
        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)

        // The terminal is redrawn whether or not its text changed; only actual changes need a frame.
        val texts = textFrames.filterIndexed { i, frame -> i == 0 || frame.content != textFrames[i - 1].content }

        val progress = ProgressBar(50)
        NIOUtils.writableFileChannel(outputFile.absolutePath).use { out ->
            val encoder = VariableFrameRateEncoder(out)
            try {
                val lastScreenMs = lastFrameStartMs(screenRecording)
                useFrameGrab(screenRecording) { grab -> renderFrames(grab, texts, lastScreenMs, recordedForMs, encoder, progress) }
            } catch (e: Throwable) {
                // Still write the index, so what was rendered before the failure can be played.
                runCatching { encoder.finish() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
            encoder.finish()
        }
        System.err.println()
        System.err.println()
        System.err.println("Rendering complete! If you're sharing on Twitter be sure to tag us \uD83D\uDE04 @|bold @mobile__dev|@".render())
    }

    private fun renderFrames(
        grab: FrameGrab,
        texts: List<Frame>,
        lastScreenMs: Long,
        recordedForMs: Long?,
        encoder: VariableFrameRateEncoder,
        progress: ProgressBar,
    ) {
        val minFrameMs = 1000L / maxFPS
        val recordingMs = (grab.videoTrack.meta.totalDuration * 1000).roundToLong()
        // Past the end of the recording, the last screen stays on show.
        val endMs = maxOf(outputDurationMs(recordingMs, lastScreenMs, texts, recordedForMs), minFrameMs)

        // Each screen frame is copied as soon as it is decoded: the decoder reuses one buffer for
        // every frame, so decoding the next one overwrites the last.
        var screen = grab.nativeFrameWithMetadata!!.picture.deepCopy()
        var nextScreen: PictureWithMetadata? = grab.nativeFrameWithMetadata
        // texts[textIndex] is on screen. Before the first text's own time, the first text is. There
        // are no texts at all when the run failed before the view first rendered.
        var textIndex = 0

        var atMs = 0L
        while (atMs < endMs) {
            // Every change up to now, so of several within one frame, the latest is shown.
            while (nextScreen != null && nextScreen.timestampMs() <= atMs) {
                screen = nextScreen.picture.deepCopy()
                nextScreen = grab.nativeFrameWithMetadata
            }
            while (textIndex + 1 < texts.size && texts[textIndex + 1].timestamp <= atMs) textIndex++

            val nextScreenMs = nextScreen?.timestampMs() ?: Long.MAX_VALUE
            val nextTextMs = texts.getOrNull(textIndex + 1)?.timestamp ?: Long.MAX_VALUE
            val untilMs = minOf(maxOf(minOf(nextScreenMs, nextTextMs), atMs + minFrameMs), endMs)

            encoder.encode(compose(screen, texts.getOrNull(textIndex)), startMs = atMs, durationMs = untilMs - atMs)
            atMs = untilMs
            progress.set(atMs / endMs.toFloat())
        }
    }

    private fun compose(screen: Picture, textFrame: Frame?): Picture {
        val text = textFrame?.let { it.content.decodeBase64()!!.string(Charsets.UTF_8).stripAnsiCodes() } ?: ""
        val frame = frameRenderer.render(outputWidthPx, outputHeightPx, AWTUtil.toBufferedImage(screen), text)
        return AWTUtil.fromBufferedImageRGB(frame)
    }

    /** Copies the pixels too, unlike Picture.copyPicture, which shares them. */
    private fun Picture.deepCopy(): Picture = Picture.createCropped(width, height, color, crop).also { it.copyFrom(this) }

    private fun PictureWithMetadata.timestampMs(): Long = (timestamp * 1000).roundToLong()

    companion object {
        // How long the final terminal screen stays on show when it comes after the recording ends.
        private const val FINAL_SCREEN_HOLD_MS = 2_000L

        // Stock Android below API 34 stops screenrecord after 3 minutes.
        private const val RECORDER_TIME_LIMIT_MS = 180_000L

        /**
         * Android's screenrecord writes a frame only when the screen changes, so its recording ends at the
         * last change, not when it was stopped. Commands that finish after that, such as assertions on a
         * screen that has settled, would never show as completed. Lengthen the video to show them.
         *
         * Not past 3 minutes, though, when the host recorded for longer but the recording's last frame starts
         * within them: the recorder may have stopped at its time limit, and what was on screen afterwards is
         * unknown. Until then it was still recording, so the last screen is what was showing. The recording's
         * own length can't decide this: the last frame is given the length of the one before it, so a recording
         * stopped at 3 minutes can run seconds past them. On a device without the limit, a recording whose last
         * change came before 3 minutes is cut there too: the CLI can't tell the two apart, as it doesn't know
         * the device's API level.
         *
         * @param lastScreenMs when the recording's last frame starts.
         */
        internal fun outputDurationMs(recordingMs: Long, lastScreenMs: Long, textFrames: List<Frame>, recordedForMs: Long?): Long {
            val lastTextMs = textFrames.maxOfOrNull { it.timestamp } ?: return recordingMs
            val lengthenedMs = maxOf(recordingMs, lastTextMs + FINAL_SCREEN_HOLD_MS)
            val mayHaveHitTimeLimit = recordedForMs != null &&
                recordedForMs > RECORDER_TIME_LIMIT_MS &&
                lastScreenMs <= RECORDER_TIME_LIMIT_MS
            return if (mayHaveHitTimeLimit) minOf(lengthenedMs, maxOf(recordingMs, RECORDER_TIME_LIMIT_MS)) else lengthenedMs
        }
    }

    /** When [recording]'s last frame starts, read from its index without decoding any frames. */
    private fun lastFrameStartMs(recording: File): Long = NIOUtils.readableChannel(recording).use { channel ->
        val track = MP4Demuxer.createMP4Demuxer(channel).videoTracks.first()
        val lastPts = generateSequence { track.nextFrame() }.lastOrNull()?.ptsD ?: 0.0
        (lastPts * 1000).roundToLong()
    }

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }
}
