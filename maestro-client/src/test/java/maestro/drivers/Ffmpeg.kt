package maestro.drivers

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the host `ffmpeg` / `ffprobe` binaries used by the screen-recording tests to
 * validate stitched `.mp4` output with a strict, independent decoder (jcodec — the library that
 * wrote the file — is too lenient; it re-reads mangled samples without complaint).
 *
 * Binaries are located via `MAESTRO_TEST_FFMPEG` / `MAESTRO_TEST_FFPROBE`, then common install
 * locations, then `PATH`. Tests should `assumeTrue([available])` and skip when they are missing.
 */
internal object Ffmpeg {

    val ffmpeg: String? by lazy { locate("ffmpeg") }
    val ffprobe: String? by lazy { locate("ffprobe") }

    fun available(): Boolean = ffmpeg != null && ffprobe != null

    /**
     * Fully decodes [file] and returns real decoder/error output — empty string == a clean decode.
     *
     * One benign line is filtered out: the `null` muxer's "non monotonically increasing dts"
     * warning. That is not a decode error; it is an artifact of the null muxer rescaling a
     * variable-frame-rate stream's timestamps onto a coarse timebase, and it fires even on a
     * pristine, untouched `screenrecord` capture. The actual decode-time-stamps remain strictly
     * increasing in the source's native timescale, so it says nothing about sample integrity — the
     * corruption we gate against surfaces as "Invalid NAL unit size" / "missing picture" instead.
     */
    fun decodeErrors(file: File): String {
        val (_, out) = run(ffmpeg!!, "-v", "error", "-i", file.absolutePath, "-f", "null", "-")
        return out.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.contains("non monotonically increasing dts") }
            .filterNot { it.contains("Last message repeated") }
            .joinToString("\n")
            .trim()
    }

    /**
     * Re-encodes [file] with libx264 (forcing a full decode of every sample) and returns the number
     * of frames in the result. A value below the input's frame count means frames were dropped —
     * e.g. because of colliding/non-monotonic timestamps.
     */
    fun reencodedFrameCount(file: File, tempDir: Path): Long {
        val reenc = tempDir.resolve("reenc-${file.name}").toFile()
        val (code, out) = run(
            ffmpeg!!, "-v", "error", "-i", file.absolutePath,
            "-c:v", "libx264", "-preset", "ultrafast", "-y", reenc.absolutePath
        )
        check(code == 0) { "ffmpeg re-encode of ${file.name} failed ($code): $out" }
        return decodedFrameCount(reenc)
    }

    /** Number of frames obtained by actually decoding the video stream (not the container's count). */
    fun decodedFrameCount(file: File): Long =
        probe(file, "stream=nb_read_frames", countFrames = true).trim().toLong()

    /** Per-packet flag strings in stream order; a `K` marks a keyframe / sync sample. */
    fun packetFlags(file: File): List<String> =
        run(
            ffprobe!!, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "packet=flags", "-of", "csv=p=0", file.absolutePath
        ).second.trim().lines().filter { it.isNotBlank() }

    fun durationSeconds(file: File): Double = probe(file, "format=duration").trim().toDouble()

    private fun probe(file: File, entries: String, countFrames: Boolean = false): String {
        val cmd = mutableListOf(ffprobe!!, "-v", "error")
        if (countFrames) cmd += listOf("-count_frames", "-select_streams", "v:0")
        cmd += listOf("-show_entries", entries, "-of", "csv=p=0", file.absolutePath)
        return run(*cmd.toTypedArray()).second
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("timed out: ${cmd.joinToString(" ")}")
        }
        return process.exitValue() to output
    }

    private fun locate(name: String): String? {
        System.getenv("MAESTRO_TEST_${name.uppercase()}")?.let {
            if (File(it).canExecute()) return it
        }
        listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name")
            .firstOrNull { File(it).canExecute() }
            ?.let { return it }
        return try {
            val process = ProcessBuilder("which", name).redirectErrorStream(true).start()
            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(10, TimeUnit.SECONDS)
            if (path.isNotEmpty() && File(path).canExecute()) path else null
        } catch (e: Exception) {
            null
        }
    }
}
