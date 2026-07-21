package maestro.conformance.device

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

data class Recording(val file: java.io.File?, val available: Boolean, val reason: String?)

class ScreenRecorder(private val serial: String) {

    companion object {
        private const val MIN_CAPTURE_MS = 3000L
    }

    /**
     * Start screenrecord, run [block], stop+pull. Returns the block's result and the Recording.
     * A recording failure NEVER throws — it returns Recording(null, false, reason).
     */
    fun <T> record(label: String, block: () -> T): Pair<T, Recording> {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9]"), "_")
        val devicePath = "/sdcard/maestro-conf-$safeLabel.mp4"
        val localTmpFile = createTempFile("conf-rec-", ".mp4").toFile().also { it.deleteOnExit() }

        var process: Process? = null

        // Compute --size flag based on physical screen size
        fun computeSizeArg(): String? {
            val result = Cmd.run("adb", "-s", serial, "shell", "wm", "size")
            val match = Regex("""Physical size:\s*(\d+)x(\d+)""").find(result.stdout) ?: return null
            val w = match.groupValues[1].toIntOrNull() ?: return null
            val h = match.groupValues[2].toIntOrNull() ?: return null
            val maxDim = maxOf(w, h)
            if (maxDim <= 1920) return null
            val scale = 1920.0 / maxDim
            val sw = (w * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            val sh = (h * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            return "${sw}x${sh}"
        }

        fun startRecording(): Process {
            val sizeArg = try { computeSizeArg() } catch (_: Exception) { null }
            val cmd = mutableListOf(
                "adb", "-s", serial, "shell", "screenrecord", "--time-limit", "180"
            )
            if (sizeArg != null) {
                cmd += listOf("--size", sizeArg)
            }
            cmd += devicePath
            return ProcessBuilder(cmd).start()
        }

        fun stopRecording(proc: Process) {
            try {
                Cmd.run("adb", "-s", serial, "shell", "pkill", "-INT", "screenrecord", timeoutMs = 5_000)
            } catch (_: Exception) { /* best effort */ }
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
            // Wait for the on-device screenrecord to FULLY exit before returning. SIGINT triggers
            // moov finalization; if we let the next command start a new recording while this one is
            // still shutting down, both get truncated (observed as identical tiny broken mp4s under
            // --record all). Poll pidof until it's gone, then a brief flush.
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val alive = runCatching {
                    Cmd.run("adb", "-s", serial, "shell", "pidof", "screenrecord").stdout.trim().isNotEmpty()
                }.getOrDefault(false)
                if (!alive) break
                Thread.sleep(150)
            }
            Thread.sleep(400)
        }

        // Start recording machinery — any failure here becomes Recording(null, false, reason)
        val recordingSetup: (() -> Recording)?
        var captureStartMs = 0L

        try {
            process = startRecording()
            Thread.sleep(350)
            captureStartMs = System.currentTimeMillis()
            recordingSetup = {
                val proc = process!!
                try {
                    stopRecording(proc)

                    val pullResult = Cmd.run("adb", "-s", serial, "pull", devicePath, localTmpFile.absolutePath)
                    Cmd.run("adb", "-s", serial, "shell", "rm", "-f", devicePath)

                    val durationS = mp4DurationSeconds(localTmpFile)
                    when {
                        !pullResult.ok -> Recording(null, false, "adb pull failed (exit=${pullResult.exit}): ${pullResult.stderr.trim()}")
                        !localTmpFile.exists() -> Recording(null, false, "pulled file does not exist locally")
                        localTmpFile.length() < 1024 -> Recording(null, false, "file too small (${localTmpFile.length()} bytes) — likely broken/empty mp4")
                        // screenrecord emits frames only on visual change; a static screen yields a
                        // structurally-valid mp4 with zero frames (0s duration, unplayable). Drop it
                        // so the report shows the screenshot instead of a dead 0-second player.
                        durationS < 0.5 -> Recording(null, false, "no video frames (screen was static during capture) — see screenshot")
                        else -> Recording(localTmpFile, true, null)
                    }
                } catch (e: Exception) {
                    Recording(null, false, "recording stop/pull failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Recording could not be started — run block anyway, return failure recording
            val result = block()
            return result to Recording(null, false, "recording start failed: ${e.message}")
        }

        // Run the block OUTSIDE the recording try/catch so its exception propagates
        val blockResult: T
        try {
            blockResult = block()
        } catch (blockEx: Throwable) {
            // block threw — stop recording best-effort, rethrow block's exception
            // Pad to minimum capture duration so screenrecord can finalize a valid clip
            // (the FAIL case is exactly where the video matters most).
            val elapsed = System.currentTimeMillis() - captureStartMs
            if (elapsed < MIN_CAPTURE_MS) Thread.sleep(MIN_CAPTURE_MS - elapsed)
            try {
                process?.let { stopRecording(it) }
            } catch (_: Exception) { /* best effort */ }
            throw blockEx
        }

        // Pad to minimum capture duration before stopping so screenrecord finalizes a valid clip.
        val elapsed = System.currentTimeMillis() - captureStartMs
        if (elapsed < MIN_CAPTURE_MS) Thread.sleep(MIN_CAPTURE_MS - elapsed)

        // Stop and pull
        val recording = recordingSetup()
        return blockResult to recording
    }

    /**
     * Video duration in seconds, parsed from the mp4 `mvhd` box — no ffprobe/ffmpeg needed
     * (portable). Returns 0.0 when the box is absent/unparseable, or when the timescale is zero.
     * A static-screen recording produces a structurally-valid mp4 whose mvhd duration is 0.
     */
    private fun mp4DurationSeconds(file: java.io.File): Double = try {
        val b = file.readBytes()
        var i = -1
        var k = 0
        while (k <= b.size - 4) {
            if (b[k] == 'm'.code.toByte() && b[k + 1] == 'v'.code.toByte() &&
                b[k + 2] == 'h'.code.toByte() && b[k + 3] == 'd'.code.toByte()
            ) { i = k; break }
            k++
        }
        if (i < 0) 0.0 else {
            fun u32(p: Int): Long = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
            fun u64(p: Int): Long { var v = 0L; for (j in 0 until 8) v = (v shl 8) or (b[p + j].toLong() and 0xFF); return v }
            val version = b[i + 4].toInt() and 0xFF
            // mvhd payload starts at i+4 (version byte). v0: timescale@+12, duration@+16 (32-bit).
            // v1: timescale@+20, duration@+24 (64-bit). (creation/modification are 8 vs 16 bytes.)
            val timescale: Long; val duration: Long
            if (version == 1) { timescale = u32(i + 4 + 20); duration = u64(i + 4 + 24) }
            else { timescale = u32(i + 4 + 12); duration = u32(i + 4 + 16) }
            if (timescale <= 0L) 0.0 else duration.toDouble() / timescale
        }
    } catch (_: Exception) { 0.0 }
}
