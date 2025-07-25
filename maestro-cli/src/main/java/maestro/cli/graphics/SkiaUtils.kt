package maestro.cli.graphics

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferByte
import java.awt.image.Raster

object SkiaFonts {

    val SANS_SERIF_FONT_FAMILIES = listOf("Inter", "Roboto", "Arial", "Avenir Next", "Avenir", "Helvetica Neue", "Helvetica", "Arial Nova", "Arimo", "Noto Sans", "Liberation Sans", "DejaVu Sans", "Nimbus Sans", "Clear Sans", "Lato", "Cantarell", "Arimo", "Ubuntu")
    val MONOSPACE_FONT_FAMILIES = listOf("Cascadia Code", "Source Code Pro", "Menlo", "Consolas", "Monaco", "Liberation Mono", "Ubuntu Mono", "Roboto Mono", "Lucida Console", "Monaco", "Courier New", "Courier")

    val SANS_SERIF_TYPEFACE: Typeface
    val MONOSPACE_TYPEFACE: Typeface

    init {
        val sansSerifTypeface = FontMgr.default.matchFamiliesStyle(SANS_SERIF_FONT_FAMILIES.toTypedArray(), FontStyle.NORMAL)
        if (sansSerifTypeface == null) {
            System.err.println("Failed to find a sans-serif typeface.")
        }
        SANS_SERIF_TYPEFACE = sansSerifTypeface ?: Typeface.makeEmpty()

        val monospaceTypeface = FontMgr.default.matchFamiliesStyle(MONOSPACE_FONT_FAMILIES.toTypedArray(), FontStyle.NORMAL)
        if (monospaceTypeface == null) {
            System.err.println("Failed to find a monospace typeface.")
        }
        MONOSPACE_TYPEFACE = monospaceTypeface ?: Typeface.makeEmpty()
    }
}

// https://stackoverflow.com/a/70852824
fun Image.toBufferedImage(): BufferedImage {
    val storage = Bitmap()
    storage.allocPixelsFlags(ImageInfo.makeS32(this.width, this.height, ColorAlphaType.PREMUL), false)
    Canvas(storage).drawImage(this, 0f, 0f)

    val bytes = storage.readPixels(storage.imageInfo, (this.width * 4), 0, 0)!!
    val buffer = DataBufferByte(bytes, bytes.size)
    val raster = Raster.createInterleavedRaster(
        buffer,
        this.width,
        this.height,
        this.width * 4, 4,
        intArrayOf(2, 1, 0, 3),     // BGRA order
        null
    )
    val colorModel = ComponentColorModel(
        ColorSpace.getInstance(ColorSpace.CS_sRGB),
        true,
        false,
        Transparency.TRANSLUCENT,
        DataBuffer.TYPE_BYTE
    )

    return BufferedImage(colorModel, raster!!, false, null)
}

fun Rect.toRRect(radii: Float): RRect {
    return RRect.makeLTRB(this.left, this.top, this.right, this.bottom, radii)
}

fun Rect.toRRect(tlRad: Float, trRad: Float, brRad: Float, blRad: Float): RRect {
    return RRect.makeLTRB(this.left, this.top, this.right, this.bottom, tlRad, trRad, brRad, blRad)
}