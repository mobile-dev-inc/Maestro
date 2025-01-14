package maestro.cli.graphics

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Rect
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.min

class SkiaTextClipper {

    private val terminalTextStyle = TextStyle().apply {
        fontFamilies = SkiaFonts.MONOSPACE_FONT_FAMILIES.toTypedArray()
        fontSize = 24f
        color = Color.WHITE
    }

    fun renderClippedText(canvas: Canvas, rect: Rect, text: String, focusedLine: Int) {
        val p = createParagraph(text, rect.width)
        val focusedLineRange = getRangeForLine(text, focusedLine)
        val focusedLineBottom = p.getRectsForRange(
            start = focusedLineRange.first,
            end = focusedLineRange.second,
            rectHeightMode = RectHeightMode.MAX,
            rectWidthMode = RectWidthMode.MAX
        ).maxOf { it.rect.bottom }
        val offsetY = min(0f, rect.height - focusedLineBottom)
        canvas.save()
        canvas.clipRect(rect)
        p.paint(canvas, rect.left, rect.top + offsetY)
        canvas.restore()
    }

    private fun getRangeForLine(text: String, lineIndex: Int): Pair<Int, Int> {
        var start = 0
        var end = 0
        var currentLine = 0
        while (currentLine <= lineIndex) {
            start = end
            end = text.indexOf('\n', start + 1)
            if (end == -1) {
                end = text.length
                break
            }
            currentLine++
        }
        return Pair(start, end)
    }

    private fun createParagraph(text: String, width: Float): Paragraph {
        val fontCollection = FontCollection().setDefaultFontManager(FontMgr.default)
        return ParagraphBuilder(ParagraphStyle(), fontCollection)
            .pushStyle(terminalTextStyle)
            .addText(text)
            .build()
            .apply { layout(width) }
    }
}
