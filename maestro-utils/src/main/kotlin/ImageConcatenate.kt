import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

object ImageConcatenate {
    @Throws(Exception::class)
    fun concatenate(images: Array<BufferedImage>, resultFilePath: String) {
        var totalWidth = 0
        var totalHeight = 0

        for (image in images) {
            totalWidth += image.width
            totalHeight = max(image.height.toDouble(), totalHeight.toDouble()).toInt()
        }

        val img = BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB)

        var currentImageWidth = 0

        for (image in images) {
            img.createGraphics().drawImage(image, currentImageWidth, 0, null)
            currentImageWidth += image.width
        }
        ImageIO.write(img, "png", File("$resultFilePath.png"))
    }
}