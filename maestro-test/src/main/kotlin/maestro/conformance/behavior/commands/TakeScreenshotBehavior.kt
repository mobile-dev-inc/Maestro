package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*
import okio.Buffer
import javax.imageio.ImageIO

class TakeScreenshotBehavior : CommandBehavior {
    override val name = "takeScreenshot"
    override val coverage = Coverage.DEVICE_LEVEL

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val expected = mapOf("decodes" to true, "nonZeroDims" to true, "notBlank" to true)
        // Poll up to ~5s: some toolkits (Flutter, RN/Hermes) paint their first frame noticeably
        // after the activity is up, so a single capture right after launch can catch a pre-paint
        // blank frame. Re-capture until the screen has content (or time out). Also samples a 5x5
        // grid (not just 3 points) so a sparse layout isn't mistaken for blank.
        val deadline = System.currentTimeMillis() + 5_000L
        var lastActual: Map<String, Any?> = mapOf("attempts" to 0)
        var attempts = 0
        while (true) {
            attempts++
            val buf = Buffer()
            ctx.driver.takeScreenshot(buf, compressed = false)   // act
            val bytes = buf.readByteArray()
            val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
            if (img != null && img.width > 0 && img.height > 0) {
                val xs = listOf(0, img.width / 4, img.width / 2, img.width * 3 / 4, img.width - 1)
                val ys = listOf(0, img.height / 4, img.height / 2, img.height * 3 / 4, img.height - 1)
                val colors = xs.flatMap { x ->
                    ys.map { y -> img.getRGB(x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1)) }
                }.toSet()
                val notBlank = colors.size >= 2 || bytes.size > 50_000
                lastActual = mapOf("width" to img.width, "height" to img.height,
                    "distinctSamples" to colors.size, "attempts" to attempts)
                if (notBlank) {
                    return CommandOutcome(Verdict.pass(), OracleKind.RETURN_VALUE, expected, lastActual, emptyMap())
                }
            } else {
                lastActual = mapOf("bytes" to bytes.size, "attempts" to attempts)
            }
            if (System.currentTimeMillis() >= deadline) {
                return CommandOutcome(Verdict.fail("screenshot looks uniformly blank"),
                    OracleKind.RETURN_VALUE, expected, lastActual, emptyMap())
            }
            Thread.sleep(250)
        }
    }
}
