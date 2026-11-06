package maestro.cli.graphics

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.toImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class SkiaFrameRenderer : FrameRenderer {

    private val backgroundImage = ImageIO.read(SkiaFrameRenderer::class.java.getResource("/record-background.jpg")!!).toImage()

    private val shadowColor = Color.makeARGB(100, 0, 0, 0)

    private val scenePadding = 40f
    private val sceneGap = 40f

    private val headerBgColor = Color.makeARGB(50, 255, 255, 255)
    private val headerHeight = 60f
    private val headerFont = Font(SkiaFonts.SANS_SERIF_TYPEFACE, 22f)
    private val headerTextColor = Color.makeARGB(200, 0, 0, 0)
    private val headerText = "Record your own using ${'$'} maestro record YourFlow.yaml"

    private val headerButtonColor = Color.makeARGB(50, 255, 255, 255)
    private val headerButtonSize = 20f
    private val headerButtonGap = 10f
    private val headerButtonMx = 20f

    private val footerBgColor = Color.makeARGB(50, 255, 255, 255)
    private val footerHeight = 60f
    private val footerFont = Font(SkiaFonts.SANS_SERIF_TYPEFACE, 22f)
    private val footerTextColor = Color.makeARGB(200, 0, 0, 0)
    private val footerText = "maestro.mobile.dev"

    private val terminalBgColor = Color.makeARGB(220, 0, 0, 0)
    private val terminalContentPadding = 40f

    private val textClipper = SkiaTextClipper()

    private val deviceCornerRadius = 20f

    private var staticLayer: StaticLayer? = null

    override fun render(
        outputWidthPx: Int,
        outputHeightPx: Int,
        screen: BufferedImage,
        text: String
    ): BufferedImage {
        val layer = staticLayerFor(LayerKey(outputWidthPx, outputHeightPx, screen.width, screen.height))
        return Surface.makeRasterN32Premul(outputWidthPx, outputHeightPx).use { surface ->
            val canvas = surface.canvas
            canvas.drawImage(layer.image, 0f, 0f)
            drawScreen(canvas, layer.deviceRect, screen)
            drawTerminalContent(canvas, layer.terminalContentRect, text)
            surface.makeImageSnapshot().toBufferedImage()
        }
    }

    /**
     * Everything that does not change from frame to frame: the background, both shadows and the
     * terminal's chrome. The shadows' blurs are the costliest part of a frame, so they are drawn
     * once and reused for as long as the output size and the screen's dimensions stay the same.
     */
    private fun staticLayerFor(key: LayerKey): StaticLayer {
        staticLayer?.let { if (it.key == key) return it }
        staticLayer?.image?.close()
        return drawStaticLayer(key).also { staticLayer = it }
    }

    private fun drawStaticLayer(key: LayerKey): StaticLayer {
        val outputWidthPx = key.outputWidthPx.toFloat()
        val outputHeightPx = key.outputHeightPx.toFloat()
        return Surface.makeRasterN32Premul(key.outputWidthPx, key.outputHeightPx).use { surface ->
            val canvas = surface.canvas
            val fullScreenRect = Rect(0f, 0f, outputWidthPx, outputHeightPx)
            canvas.drawImageRect(backgroundImage, fullScreenRect)

            val containerRect = fullScreenRect.inflate(-scenePadding)
            val deviceRect = drawDeviceShadow(canvas, containerRect, key.screenWidthPx, key.screenHeightPx)
            val terminalContentRect = drawTerminal(canvas, containerRect, deviceRect)

            StaticLayer(key, surface.makeImageSnapshot(), deviceRect, terminalContentRect)
        }
    }

    private fun drawDeviceShadow(canvas: Canvas, containerRect: Rect, screenWidthPx: Int, screenHeightPx: Int): Rect {
        val deviceImageScale = containerRect.height / screenHeightPx.toFloat()
        var deviceImageRect = Rect(0f, 0f, screenWidthPx.toFloat(), screenHeightPx.toFloat()).scale(deviceImageScale)
        deviceImageRect = deviceImageRect.offset(containerRect.right - deviceImageRect.right, containerRect.top)
        // drawRectShadow leaves the rect itself unshaded, so the screen, drawn on top each frame,
        // covers nothing of the shadow but the antialiased pixels along its own edge.
        canvas.drawRectShadow(deviceImageRect.toRRect(deviceCornerRadius), 0f, 0f, 20f, 0.5f, shadowColor)
        return deviceImageRect
    }

    private fun drawScreen(canvas: Canvas, deviceImageRect: Rect, screen: BufferedImage) {
        canvas.save()
        canvas.clipRRect(deviceImageRect.toRRect(deviceCornerRadius), true)
        canvas.drawImageRect(screen.toImage(), deviceImageRect)
        canvas.restore()
    }

    /** Draws the terminal's shadow, header, footer and background, and returns where its text goes. */
    private fun drawTerminal(canvas: Canvas, containerRect: Rect, imageRect: Rect): Rect {
        val terminalRect = Rect(containerRect.left, containerRect.top, imageRect.left - sceneGap, containerRect.bottom)
        val terminalRectRounded = terminalRect.toRRect(20f)
        canvas.drawRectShadow(terminalRectRounded, 0f, 0f, 20f, 0.5f, shadowColor)

        val headerRect = drawHeader(canvas, terminalRect)
        val footerRect = drawFooter(canvas, terminalRect)

        val contentRect = Rect.makeLTRB(terminalRect.left, headerRect.bottom, terminalRect.right, footerRect.top)
        canvas.drawRect(contentRect, Paint().apply { color = terminalBgColor })

        return Rect.makeLTRB(
            l = contentRect.left + terminalContentPadding,
            t = contentRect.top + terminalContentPadding,
            r = contentRect.right - terminalContentPadding,
            b = contentRect.bottom - terminalContentPadding / 4f,
        )
    }

    private fun drawFooter(canvas: Canvas, terminalRect: Rect): Rect {
        val footerRect = Rect.makeXYWH(terminalRect.left, terminalRect.bottom - footerHeight, terminalRect.width, footerHeight)
        val headerRectRounded = footerRect.toRRect(0f, 0f, 20f, 20f)
        canvas.drawRRect(headerRectRounded, Paint().apply { color = footerBgColor })

        drawFooterText(canvas, footerRect)

        return footerRect
    }

    private fun drawFooterText(canvas: Canvas, footerRect: Rect) {
        val paint = Paint().apply {
            color = footerTextColor
        }
        val textRect = footerFont.measureText(footerText, paint)
        val x = footerRect.left + (footerRect.width - textRect.width) / 2
        val y = footerRect.top + footerRect.height / 2 + textRect.height / 3
        canvas.drawString(footerText, x, y, footerFont, paint)
    }

    private fun drawHeader(canvas: Canvas, terminalRect: Rect): Rect {
        val headerRect = Rect.makeXYWH(terminalRect.left, terminalRect.top, terminalRect.width, headerHeight)
        val headerRectRounded = headerRect.toRRect(20f, 20f, 0f, 0f)
        canvas.drawRRect(headerRectRounded, Paint().apply { color = headerBgColor })

        drawHeaderButtons(canvas, headerRect)
        drawHeaderText(canvas, headerRect)

        return headerRect
    }

    private fun drawHeaderButtons(canvas: Canvas, headerRect: Rect) {
        var centerX = headerRect.left + headerButtonMx + headerButtonSize / 2
        val centerY = headerRect.top + headerRect.height / 2

        repeat(3) {
            canvas.drawCircle(centerX, centerY, headerButtonSize / 2, Paint().apply { color = headerButtonColor })
            centerX += headerButtonSize + headerButtonGap
        }
    }

    private fun drawHeaderText(canvas: Canvas, headerRect: Rect) {
        val paint = Paint().apply {
            color = headerTextColor
        }
        val textRect = headerFont.measureText(headerText, paint)
        val x = headerRect.left + (headerRect.width - textRect.width) / 2
        val y = headerRect.top + headerRect.height / 2 + textRect.height / 3
        canvas.drawString(headerText, x, y, headerFont, paint)
    }

    private fun drawTerminalContent(canvas: Canvas, paddedContentRect: Rect, string: String) {
        val focusedLineIndex = getFocusedLineIndex(string)
        val focusedLinePadding = 5
        textClipper.renderClippedText(canvas, paddedContentRect, string, focusedLineIndex + focusedLinePadding)
    }

    private fun getFocusedLineIndex(text: String): Int {
        val lines = text.lines()
        val indexOfFirstPendingLine = lines.indexOfFirst { it.contains("\uD83D\uDD32") }
        if (indexOfFirstPendingLine != -1) return indexOfFirstPendingLine
        val indexOfLastCheck = lines.indexOfLast { it.contains("✅") }
        if (indexOfLastCheck != -1) return indexOfLastCheck
        return 0
    }

    private data class LayerKey(
        val outputWidthPx: Int,
        val outputHeightPx: Int,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
    )

    private class StaticLayer(
        val key: LayerKey,
        val image: Image,
        val deviceRect: Rect,
        val terminalContentRect: Rect,
    )
}
