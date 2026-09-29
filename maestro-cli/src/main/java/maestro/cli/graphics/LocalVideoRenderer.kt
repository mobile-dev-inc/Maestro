package maestro.cli.graphics

import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import okio.ByteString.Companion.decodeBase64
import org.jcodec.api.PictureWithMetadata
import org.jcodec.common.io.NIOUtils
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
) : VideoRenderer {

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
    ) {
        System.err.println()
        System.err.println("@|bold Rendering video - This may take some time...|@".render())
        System.err.println()
        System.err.println(outputFile.absolutePath)

        val uploadProgress = ProgressBar(50)
        NIOUtils.writableFileChannel(outputFile.absolutePath).use { out ->
            JcodecHoldFrameEncoder(out, Rational.R(outputFPS, 1), outputWidthPx, outputHeightPx).use { encoder ->
                useFrameGrab(screenRecording) { grab ->
                    val outputDurationSeconds = grab.videoTrack.meta.totalDuration
                    val outputFrameCount = (outputDurationSeconds * outputFPS).toInt()
                    var curFrame: PictureWithMetadata = grab.nativeFrameWithMetadata!!
                    var nextFrame: PictureWithMetadata? = grab.nativeFrameWithMetadata
                    var prevPlanes: Array<ByteArray>? = null
                    var prevText: String? = null
                    var screenImage: BufferedImage? = null
                    var haveEncoded = false
                    (0..outputFrameCount).forEach { frameIndex ->
                        val currentTimestampSeconds = frameIndex.toDouble() / outputFPS

                        // !! Due to smart cast limitation: https://youtrack.jetbrains.com/issue/KT-7186
                        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                        while (nextFrame != null && nextFrame!!.timestamp <= currentTimestampSeconds) {
                            curFrame = nextFrame!!
                            nextFrame = grab.nativeFrameWithMetadata
                        }

                        val picture = curFrame.picture
                        val curTextFrame = textFrames.lastOrNull { frame -> frame.timestamp.div(1000.0) <= currentTimestampSeconds } ?: textFrames.first()
                        val curText = curTextFrame.content.decodeBase64()!!.string(Charsets.UTF_8).stripAnsiCodes()
                        val pictureUnchanged = prevPlanes != null && picture.data.contentDeepEquals(prevPlanes)
                        if (haveEncoded && pictureUnchanged && curText == prevText) {
                            encoder.holdPrevious()
                        } else {
                            if (!pictureUnchanged || screenImage == null) {
                                screenImage = AWTUtil.toBufferedImage(picture)
                                prevPlanes = Array(picture.data.size) { index -> picture.data[index].copyOf() }
                            }
                            val outputImage = frameRenderer.render(outputWidthPx, outputHeightPx, checkNotNull(screenImage), curText)
                            encoder.encodeImage(outputImage)
                            prevText = curText
                            haveEncoded = true
                        }

                        uploadProgress.set(frameIndex / outputFrameCount.toFloat())
                    }
                }
            }
        }
        System.err.println()
        System.err.println()
        System.err.println("Rendering complete! If you're sharing on Twitter be sure to tag us \uD83D\uDE04 @|bold @mobile__dev|@".render())
    }

    private fun String.stripAnsiCodes(): String {
        return replace("\\u001B\\[[;\\d]*[mH]".toRegex(), "")
    }
}
