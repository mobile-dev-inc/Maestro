package maestro.conformance.device

import dadb.Dadb
import maestro.drivers.AndroidDriver
import java.io.File

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

        // Cap the userdata partition to 2 GB for constrained-disk portability; conformance fixtures need little storage.
        runCatching {
            val configIni = File(System.getProperty("user.home"), ".android/avd/$name.avd/config.ini")
            val lines = configIni.readLines()
                .filter { !it.startsWith("disk.dataPartition.size") }
                .toMutableList()
            lines += "disk.dataPartition.size=2048M"
            configIni.writeText(lines.joinToString("\n") + "\n")
        }.onFailure { e ->
            println("Warning: could not cap AVD data partition size: ${e.message}")
        }

        emulator = ProcessBuilder(
            "emulator", "@$name",
            "-no-snapshot", "-no-window", "-no-audio", "-no-boot-anim",
            "-accel", "on", "-no-metrics", "-ports", "$consolePort,$adbPort",
        ).redirectErrorStream(true).start()

        try {
            waitForBoot()
            pinGboardIme()

            // Install Maestro APKs via the adb CLI before opening the driver.
            // dadb.install() uses the "exec:cmd" ADB transport which hangs indefinitely on API 24
            // (Android 7.0) — the exec: channel never closes after streaming large binaries.
            // Using "adb install" (shell: transport) sidesteps this entirely and works on all API levels.
            // We pass reinstallDriver=false so AndroidDriver.open() sees the packages already present
            // and skips its own dadb.install() path.
            installApksViaAdbCli()

            // Retry driver.open() up to 3 times: on older API levels (e.g. 24) the gRPC
            // instrumentation server can transiently fail to bind even after awaitLaunch() sees
            // the TCP port — a brief close/sleep/reopen cycle with a fresh dadb+driver instance
            // recovers it reliably.
            var driver: AndroidDriver? = null
            var lastOpenException: Exception? = null
            for (attempt in 1..3) {
                // Use Dadb.list() (via the adb server) rather than Dadb.create() (direct TCP).
                // Direct connections on API 24 cause gRPC UNAVAILABLE due to interference between
                // the raw dadb channel and the adb server's existing connection to port 5555.
                // Routing through the adb server matches what AttachedDeviceProvider does and
                // avoids this API 24 transport incompatibility.
                val dadb = Dadb.list().find { it.toString() == serial }
                    ?: error("Device $serial not found via adb server after emulator start")
                val candidate = AndroidDriver(dadb, emulatorName = serial, reinstallDriver = false)
                try {
                    candidate.open()
                    // Brief stabilisation sleep: awaitLaunch() only tests TCP connectivity.
                    // On API 24 the gRPC server can still be mid-init when the port first opens;
                    // a short wait lets the Netty event loop fully settle before the first RPC.
                    Thread.sleep(2000)
                    // Warm-up: verify the gRPC server can actually serve requests before returning.
                    // deviceInfo() is lightweight and proves the channel works end-to-end.
                    candidate.deviceInfo()
                    driver = candidate
                    lastOpenException = null
                    break
                } catch (e: Exception) {
                    lastOpenException = e
                    println("driver.open()/deviceInfo() attempt $attempt/3 failed: ${e.message} — retrying in 5s")
                    runCatching { candidate.close() }
                    Thread.sleep(5000)
                }
            }
            if (driver == null) throw lastOpenException!!
            return DeviceHandle(serial, driver, spec.apiLevel, userSupplied = false,
                image = image, deviceProfile = "pixel_6", abi = abi)
        } catch (e: Exception) {
            // Self-clean on ANY acquire failure so no leaked emulator/qemu/AVD cascades into the next API.
            println("acquire() failed for $name ($serial): ${e.message} — tearing down before rethrowing.")
            tearDown(name)
            throw e
        }
    }

    /**
     * Pre-install the Maestro driver and server APKs via the adb CLI.
     *
     * dadb.install() uses the ADB "exec:cmd" transport which hangs indefinitely on API 24 when
     * streaming the ~12 MB driver APK — the channel never receives the terminal response.
     * "adb install" (the ordinary adb command) uses the "shell:" transport, which works correctly
     * on all API levels we target (24–36).
     */
    private fun installApksViaAdbCli() {
        val resources = listOf("/maestro-app.apk", "/maestro-server.apk")
        for (resource in resources) {
            val tmp = File.createTempFile("maestro-install", ".apk")
            try {
                FreshAvdProvider::class.java.getResourceAsStream(resource)
                    ?.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                    ?: error("Missing classpath resource: $resource")

                val result = Cmd.run(
                    "adb", "-s", serial, "install", "-t", tmp.absolutePath,
                    timeoutMs = 120_000,
                )
                if (!result.ok) {
                    error("adb install $resource failed (exit ${result.exit}): ${result.stdout} ${result.stderr}")
                }
                println("Installed $resource via adb CLI (exit ${result.exit})")
            } finally {
                tmp.delete()
            }
        }
    }

    private fun waitForBoot(timeoutMs: Long = 180_000) {
        Cmd.run("adb", "-s", serial, "wait-for-device", timeoutMs = timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val booted = Cmd.run("adb", "-s", serial, "shell", "getprop", "sys.boot_completed").stdout.trim() == "1"
            val animStopped = Cmd.run("adb", "-s", serial, "shell", "getprop", "init.svc.bootanim").stdout.trim() == "stopped"
            val pkgResult = Cmd.run("adb", "-s", serial, "shell", "cmd", "package", "list", "packages")
            val pkgReady = pkgResult.ok && pkgResult.stdout.lines().count { it.isNotBlank() } > 20
            if (booted && animStopped && pkgReady) {
                // Let adbd/installd finish stabilising before the caller attempts APK installs.
                Thread.sleep(3000)
                return
            }
            Thread.sleep(2000)
        }
        error("Emulator $serial did not become install-ready within ${timeoutMs}ms")
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

    /**
     * Tear down the emulator process, qemu grandchild, and AVD for the given name.
     * Called both from release() and from acquire()'s catch block to guarantee no leaks.
     */
    private fun tearDown(name: String) {
        runCatching { Cmd.run("adb", "-s", serial, "emu", "kill") }
        emulator?.destroyForcibly()
        // destroyForcibly() kills the emulator launcher but NOT the qemu-system grandchild it spawned.
        // Reap it explicitly by the ports we launched on, or it leaks and holds the port (maestro-device lesson).
        runCatching { Cmd.run("/bin/sh", "-c", "pkill -9 -f 'qemu-system.*-ports $consolePort,$adbPort'") }
        // Wait (break as soon as free) for the adb port to actually free before the next acquire.
        for (i in 0 until 20) {
            if (Cmd.run("/bin/sh", "-c", "lsof -nP -iTCP:$adbPort -sTCP:LISTEN").exit != 0) break
            Thread.sleep(1000)
        }
        runCatching { Cmd.run("adb", "kill-server") }
        runCatching { Cmd.run("/bin/sh", "-c", "avdmanager delete avd -n $name") }
    }

    override fun release(handle: DeviceHandle) {
        runCatching { handle.driver.close() }
        tearDown("maestro-conformance-api${handle.apiLevel}")
    }

    companion object {
        /**
         * Detect the ABI to use for system images based on the host architecture.
         * Only arm64-v8a (Apple Silicon / aarch64) hosts are supported; throws on anything else.
         */
        fun detectHostAbi(): String {
            val arch = System.getProperty("os.arch") ?: ""
            require(arch.contains("aarch64") || arch.contains("arm")) {
                "FreshAvdProvider only supports arm64-v8a hosts (os.arch='$arch'). Use --device for non-ARM hosts."
            }
            return "arm64-v8a"
        }

        /**
         * Return the system-image variant for a given API level.
         * API 36+ requires the ps16k page-size variant; earlier levels use the standard google_apis image.
         */
        fun variantFor(api: Int): String = if (api >= 36) "google_apis_ps16k" else "google_apis"
    }
}
