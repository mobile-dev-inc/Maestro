package maestro.conformance.device

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

data class Recording(val file: java.io.File?, val available: Boolean, val reason: String?)

class ScreenRecorder(private val serial: String) {

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
            Thread.sleep(600)
        }

        // Start recording machinery — any failure here becomes Recording(null, false, reason)
        val recordingSetup: (() -> Recording)?

        try {
            process = startRecording()
            Thread.sleep(350)
            recordingSetup = {
                val proc = process!!
                try {
                    stopRecording(proc)

                    val pullResult = Cmd.run("adb", "-s", serial, "pull", devicePath, localTmpFile.absolutePath)
                    Cmd.run("adb", "-s", serial, "shell", "rm", "-f", devicePath)

                    when {
                        !pullResult.ok -> Recording(null, false, "adb pull failed (exit=${pullResult.exit}): ${pullResult.stderr.trim()}")
                        !localTmpFile.exists() -> Recording(null, false, "pulled file does not exist locally")
                        localTmpFile.length() < 1024 -> Recording(null, false, "file too small (${localTmpFile.length()} bytes) — likely broken/empty mp4")
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
            try {
                process?.let { stopRecording(it) }
            } catch (_: Exception) { /* best effort */ }
            throw blockEx
        }

        // Stop and pull
        val recording = recordingSetup()
        return blockResult to recording
    }
}
