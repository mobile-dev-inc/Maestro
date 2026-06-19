package maestro.conformance.device

import dadb.Dadb
import maestro.drivers.AndroidDriver

class FreshAvdProvider(private val abi: String = detectHostAbi()) : DeviceProvider {
    private val consolePort = 5554
    private val adbPort = 5555
    private val serial = "emulator-$consolePort"
    private var emulator: Process? = null

    override fun acquire(spec: DeviceSpec): DeviceHandle {
        Preflight.check()
        val variant = variantFor(spec.apiLevel)
        val image = "system-images;android-${spec.apiLevel};$variant;$abi"
        val name = "maestro-conformance-api${spec.apiLevel}"

        require(Cmd.run("/bin/sh", "-c", "yes | sdkmanager \"$image\"", timeoutMs = 600_000).ok) {
            "Failed to install system image $image"
        }
        require(Cmd.run("/bin/sh", "-c",
            "echo no | avdmanager create avd -n $name -k \"$image\" --device pixel_6 --force").ok) {
            "Failed to create AVD $name"
        }

        emulator = ProcessBuilder(
            "emulator", "@$name",
            "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim",
            "-accel", "on", "-no-metrics", "-ports", "$consolePort,$adbPort",
        ).redirectErrorStream(true).start()

        waitForBoot()
        pinGboardIme()

        val dadb = Dadb.create("localhost", adbPort)
        val driver = AndroidDriver(dadb, emulatorName = serial)
        driver.open()
        return DeviceHandle(serial, driver, spec.apiLevel, userSupplied = false)
    }

    private fun waitForBoot(timeoutMs: Long = 180_000) {
        Cmd.run("adb", "-s", serial, "wait-for-device", timeoutMs = timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val booted = Cmd.run("adb", "-s", serial, "shell", "getprop", "sys.boot_completed").stdout.trim() == "1"
            val pkg = Cmd.run("adb", "-s", serial, "shell", "cmd", "package", "list", "packages").ok
            if (booted && pkg) return
            Thread.sleep(1000)
        }
        error("Emulator $serial did not boot within ${timeoutMs}ms")
    }

    /** Keyboard commands in AndroidDriver match the GBoard package; pin it so they don't false-fail. */
    private fun pinGboardIme() {
        val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        val imes = Cmd.run("adb", "-s", serial, "shell", "ime", "list", "-s").stdout
        if (imes.contains("com.google.android.inputmethod.latin")) {
            Cmd.run("adb", "-s", serial, "shell", "ime", "enable", gboard)
            Cmd.run("adb", "-s", serial, "shell", "ime", "set", gboard)
        } else {
            println("⚠ GBoard IME not present on this image — keyboard commands may be skipped/red. " +
                "Prefer a google_apis_playstore image.")
        }
    }

    override fun release(handle: DeviceHandle) {
        runCatching { handle.driver.close() }
        runCatching { Cmd.run("adb", "-s", serial, "emu", "kill") }
        emulator?.destroyForcibly()
        // wait for the adb port to free, then reset the daemon (maestro-device's loop-pressure fix)
        repeat(10) {
            if (Cmd.run("/bin/sh", "-c", "lsof -nP -iTCP:$adbPort -sTCP:LISTEN").exit != 0) return@repeat
            Thread.sleep(1000)
        }
        Cmd.run("adb", "kill-server")
        Cmd.run("/bin/sh", "-c", "avdmanager delete avd -n maestro-conformance-api${handle.apiLevel}")
    }

    companion object {
        /**
         * Detect the ABI to use for system images based on the host architecture.
         * On Apple Silicon (aarch64) and other ARM hosts, use arm64-v8a; otherwise x86_64.
         */
        fun detectHostAbi(): String {
            val arch = System.getProperty("os.arch") ?: ""
            return if (arch.contains("aarch64") || arch.contains("arm")) "arm64-v8a" else "x86_64"
        }

        /**
         * Return the system-image variant for a given API level.
         * API 36+ requires the ps16k page-size variant; earlier levels use the standard google_apis image.
         */
        fun variantFor(api: Int): String = if (api >= 36) "google_apis_ps16k" else "google_apis"
    }
}
