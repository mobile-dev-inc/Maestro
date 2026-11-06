package maestro.cli.graphics

import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jcodec.api.FrameGrab
import org.jcodec.api.PictureWithMetadata
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Picture
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
) : VideoRenderer {

    init {
        require(maxFPS in 1..1000) { "maxFPS must be between 1 and 1000, was $maxFPS" }
    }

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
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
                useFrameGrab(screenRecording) { grab -> renderFrames(grab, texts, encoder, progress) }
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
        texts: List<AnsiResultView.Frame>,
        encoder: VariableFrameRateEncoder,
        progress: ProgressBar,
    ) {
        val minFrameMs = 1000L / maxFPS
        val endMs = maxOf((grab.videoTrack.meta.totalDuration * 1000).roundToLong(), minFrameMs)

        // Each screen frame is copied as soon as it is decoded: the decoder reuses one buffer for
        // every frame, so decoding the next one overwrites the last.
        var screen = grab.nativeFrameWithMetadata!!.picture.deepCopy()
        var nextScreen: PictureWithMetadata? = grab.nativeFrameWithMetadata
        // texts[textIndex] is on screen. Before the first text's own time, the first text is.
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

            encoder.encode(compose(screen, texts[textIndex]), startMs = atMs, durationMs = untilMs - atMs)
            atMs = untilMs
            progress.set(atMs / endMs.toFloat())
        }
    }

    private fun compose(screen: Picture, textFrame: AnsiResultView.Frame): Picture {
        val text = textFrame.content.decodeBase64()!!.string(Charsets.UTF_8).stripAnsiCodes()
        val frame = frameRenderer.render(outputWidthPx, outputHeightPx, AWTUtil.toBufferedImage(screen), text)
        return AWTUtil.fromBufferedImageRGB(frame)
    }

    /** Copies the pixels too, unlike Picture.copyPicture, which shares them. */
    private fun Picture.deepCopy(): Picture = Picture.createCropped(width, height, color, crop).also { it.copyFrom(this) }

    private fun PictureWithMetadata.timestampMs(): Long = (timestamp * 1000).roundToLong()

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }
}
