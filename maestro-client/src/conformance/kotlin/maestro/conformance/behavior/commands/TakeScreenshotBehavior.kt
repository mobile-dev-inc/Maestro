package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import okio.Buffer
import javax.imageio.ImageIO

class TakeScreenshotBehavior : CommandBehavior {
    override val name = "takeScreenshot"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val buf = Buffer()
        ctx.driver.takeScreenshot(buf, compressed = false)   // act
        val bytes = buf.readByteArray()
        val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
        val expected = mapOf("decodes" to true, "nonZeroDims" to true, "notBlank" to true)
        if (img == null || img.width == 0 || img.height == 0) {
            return CommandOutcome(Verdict.fail("screenshot did not decode / zero dims"),
                OracleKind.RETURN_VALUE, expected, mapOf("bytes" to bytes.size), emptyMap())
        }
        // "not uniformly blank": sample a few pixels, require at least two distinct colors.
        val colors = listOf(0 to 0, img.width / 2 to img.height / 2, img.width - 1 to img.height - 1)
            .map { (x, y) -> img.getRGB(x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1)) }
            .toSet()
        val notBlank = colors.size >= 2 || bytes.size > 50_000
        val actual = mapOf("width" to img.width, "height" to img.height, "distinctSamples" to colors.size)
        return CommandOutcome(
            if (notBlank) Verdict.pass() else Verdict.fail("screenshot looks uniformly blank"),
            OracleKind.RETURN_VALUE, expected, actual, emptyMap())
    }
}
