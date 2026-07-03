package maestro.drivers

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Adb helper shared by the screen-recording tests. Locates the device (env `MAESTRO_TEST_ADB` /
 * `MAESTRO_TEST_DEVICE`, defaulting to the local SDK + `emulator-5554`), runs shell commands, and
 * captures short `screenrecord` segments off a scrolling screen so recordings have real motion.
 */
internal class AdbTestDevice {

    val path: String = System.getenv("MAESTRO_TEST_ADB")
        ?: (System.getProperty("user.home") + "/android-sdk/platform-tools/adb")

    val serial: String = System.getenv("MAESTRO_TEST_DEVICE") ?: "emulator-5554"

    fun isReachable(): Boolean = File(path).exists() && runCatching { apiLevel() }.getOrNull() != null

    fun apiLevel(): Int = run("shell", "getprop", "ro.build.version.sdk").trim().toInt()

    /** Opens a scrollable screen (Settings) so recordings capture motion, not a single static frame. */
    fun openScrollableScreen() = run("shell", "am", "start", "-a", "android.settings.SETTINGS")

    /** Swipes up/down for [millis] so an in-progress recording captures many distinct frames. */
    fun scrollFor(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        var down = true
        while (System.currentTimeMillis() < deadline) {
            val (from, to) = if (down) 1500 to 500 else 500 to 1500
            run("shell", "input", "swipe", "540", "$from", "540", "$to", "150")
            down = !down
        }
    }

    /** Records a [seconds]-long segment while scrolling, pulls it into [tempDir], and returns the file. */
    fun captureSegment(index: Int, seconds: Int, tempDir: Path): File {
        val remote = "/sdcard/maestro-concat-test-$index.mp4"
        openScrollableScreen()
        val recorder = start("shell", "screenrecord", "--time-limit", "$seconds", "--bit-rate", "4000000", remote)
        scrollFor(seconds * 1000L)
        check(recorder.waitFor(30, TimeUnit.SECONDS)) { "screenrecord segment $index did not finish" }
        val local = tempDir.resolve("segment-$index.mp4").toFile()
        run("pull", remote, local.absolutePath)
        run("shell", "rm", "-f", remote)
        check(local.length() > 0) { "captured segment $index was empty" }
        return local
    }

    /** Runs `adb -s <serial> <args>`, failing on a non-zero exit or timeout; returns stdout+stderr. */
    fun run(vararg args: String): String {
        val process = start(*args)
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("adb ${args.joinToString(" ")} timed out")
        }
        check(process.exitValue() == 0) { "adb ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private fun start(vararg args: String): Process =
        ProcessBuilder(listOf(path, "-s", serial) + args).redirectErrorStream(true).start()
}
