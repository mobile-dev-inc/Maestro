package maestro.cli.graphics

import maestro.cli.runner.resultview.Frame
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jcodec.api.PictureWithMetadata
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Picture
import org.jcodec.common.model.Rational
import org.jcodec.scale.AWTUtil
import java.awt.image.BufferedImage
import java.io.File

interface FrameRenderer {
    fun render(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: BufferedImage,
        text: String,
    ): BufferedImage
}

class LocalVideoRenderer(
    private val frameRenderer: FrameRenderer,
    private val outputFile: File,
    private val outputFPS: Int,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
) {

    fun render(
        screenRecording: File,
        textFrames: List<Frame>,
    ) {
        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)

        val renderProgress = ProgressBar(50)
        NIOUtils.writableFileChannel(outputFile.absolutePath).use { out ->
            AWTSequenceEncoder(out, Rational.R(outputFPS, 1)).use { encoder ->
                useFrameGrab(screenRecording) { grab ->
                    val outputDurationSeconds = grab.videoTrack.meta.totalDuration
                    val outputFrameCount = (outputDurationSeconds * outputFPS).toInt()
                    var curFrame: PictureWithMetadata = grab.nativeFrameWithMetadata!!
                    var nextFrame: PictureWithMetadata? = grab.nativeFrameWithMetadata

                    // An output frame depends only on the screen frame and the terminal text, and
                    // both usually hold for many output frames. While neither changes, the last
                    // composed frame is encoded again rather than drawn and converted afresh.
                    var composedFrom: PictureWithMetadata? = null
                    var composedText: Frame? = null
                    var composed: Picture? = null
                    (0..outputFrameCount).forEach { frameIndex ->
                        val currentTimestampSeconds = frameIndex.toDouble() / outputFPS

                        // !! Due to smart cast limitation: https://youtrack.jetbrains.com/issue/KT-7186
                        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                        while (nextFrame != null && nextFrame!!.timestamp <= currentTimestampSeconds) {
                            curFrame = nextFrame!!
                            nextFrame = grab.nativeFrameWithMetadata
                        }

                        // No frames at all when the run failed before the view first rendered.
                        val curTextFrame = textFrames.lastOrNull { frame -> frame.timestamp.div(1000.0) <= currentTimestampSeconds } ?: textFrames.firstOrNull()
                        val outputPicture = composed.takeIf { curFrame === composedFrom && curTextFrame == composedText }
                            ?: compose(curFrame, curTextFrame).also {
                                composedFrom = curFrame
                                composedText = curTextFrame
                                composed = it
                            }
                        // Converts into a buffer of its own, so outputPicture is unchanged and can be encoded again.
                        encoder.encodeNativeFrame(outputPicture)

                        renderProgress.set(frameIndex / outputFrameCount.toFloat())
                    }
                }
            }
        }
        System.err.println()
        System.err.println()
        System.err.println("Rendering complete! If you're sharing on Twitter be sure to tag us \uD83D\uDE04 @|bold @mobile__dev|@".render())
    }

    private fun compose(screenFrame: PictureWithMetadata, textFrame: Frame?): Picture {
        val screen = AWTUtil.toBufferedImage(screenFrame.picture)
        val text = textFrame?.let { it.content.decodeBase64()!!.string(Charsets.UTF_8).stripAnsiCodes() } ?: ""
        return AWTUtil.fromBufferedImageRGB(frameRenderer.render(outputWidthPx, outputHeightPx, screen, text))
    }

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }
}
