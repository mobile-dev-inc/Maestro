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

        // Only invoke sdkmanager when the image is actually missing. Running it on every acquire is
        // slow (it re-fetches the remote repo even when installed) and fragile — it returns non-zero
        // under a concurrent sdkmanager or a transient repo hiccup, which showed up as a spurious
        // "Failed to install system image" even though the image was present locally.
        val imageInstalled = File(sdkRoot(), "system-images/android-${spec.apiLevel}/$variant/$abi")
            .let { it.isDirectory && (it.list()?.isNotEmpty() == true) }
        ensureEnoughDisk(spec.apiLevel, imageInstalled)
        if (!imageInstalled) {
            require(Cmd.run("/bin/sh", "-c", "yes | sdkmanager \"$image\"", timeoutMs = 600_000).ok) {
                "Failed to install system image $image"
            }
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
            pinUsableIme()

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

    private fun sdkRoot(): String =
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: File(System.getProperty("user.home"), "Library/Android/sdk").path

    /**
     * Cross-platform (Linux + macOS) pre-flight free-space guard. Uses `File.usableSpace` — pure JVM,
     * identical on both OSes, no `df` parsing — on the volume that holds AVDs. An emulator needs the
     * AVD userdata partition (capped to 2 GB below) plus headroom; a missing system image needs ~4 GB
     * more to download and unzip. Failing here gives an actionable message instead of a cryptic
     * emulator FATAL ("Not enough space to create userdata partition") deep inside provisioning.
     */
    private fun ensureEnoughDisk(api: Int, imageInstalled: Boolean) {
        val avdHome = File(System.getProperty("user.home"), ".android/avd").apply { mkdirs() }
        val neededGb = if (imageInstalled) 4 else 10
        val freeBytes = avdHome.usableSpace
        val neededBytes = neededGb.toLong() * 1024 * 1024 * 1024
        if (freeBytes < neededBytes) {
            val freeGb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            error(
                "Not enough free disk to provision API $api.\n" +
                    "  Free:  %.1f GB at %s\n".format(freeGb, avdHome.path) +
                    "  Need:  ~$neededGb GB (" +
                    (if (imageInstalled) "system image already installed" else "system image download ~4 GB") +
                    " + 2 GB AVD userdata + headroom)\n" +
                    "  Free space (e.g. delete unused images under ${sdkRoot()}/system-images) and retry."
            )
        }
    }

    private fun waitForBoot(timeoutMs: Long = 180_000) {
        Cmd.run("adb", "-s", serial, "wait-for-device", timeoutMs = timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val booted = Cmd.run("adb", "-s", serial, "shell", "getprop", "sys.boot_completed").stdout.trim() == "1"
            // init.svc.bootanim is "running" during the boot animation, then "stopped" on older
            // images — but EMPTY/absent on newer ones (e.g. API 36 google_apis). Treat anything that
            // isn't actively "running" as done; combined with boot_completed + pkgReady that's the
            // real install-readiness signal. (Requiring == "stopped" hung forever on API 36.)
            val animDone = Cmd.run("adb", "-s", serial, "shell", "getprop", "init.svc.bootanim").stdout.trim() != "running"
            val pkgResult = Cmd.run("adb", "-s", serial, "shell", "cmd", "package", "list", "packages")
            val pkgReady = pkgResult.ok && pkgResult.stdout.lines().count { it.isNotBlank() } > 20
            if (booted && animDone && pkgReady) {
                // Let adbd/installd finish stabilising before the caller attempts APK installs.
                Thread.sleep(3000)
                return
            }
            Thread.sleep(2000)
        }
        error("Emulator $serial did not become install-ready within ${timeoutMs}ms")
    }

    /**
     * Pin a usable soft keyboard IME so that keyboard-related commands work reliably.
     *
     * Preference order:
     *  1. GBoard (com.google.android.inputmethod.latin) — present on google_apis_playstore images.
     *  2. AOSP LatinIME (com.android.inputmethod.latin) — present on google_apis images (API ≤35)
     *     where GBoard is absent; this is the system keyboard on those images.
     *
     * AndroidDriver.isKeyboardVisible() detects both packages, so either IME produces a real PASS.
     */
    private fun pinUsableIme() {
        val imes = Cmd.run("adb", "-s", serial, "shell", "ime", "list", "-s").stdout
        when {
            imes.contains("com.google.android.inputmethod.latin") -> {
                val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
                Cmd.run("adb", "-s", serial, "shell", "ime", "enable", gboard)
                Cmd.run("adb", "-s", serial, "shell", "ime", "set", gboard)
                println("Pinned GBoard IME ($gboard)")
            }
            imes.contains("com.android.inputmethod.latin") -> {
                val aosp = "com.android.inputmethod.latin/.LatinIME"
                Cmd.run("adb", "-s", serial, "shell", "ime", "enable", aosp)
                Cmd.run("adb", "-s", serial, "shell", "ime", "set", aosp)
                println("Pinned AOSP LatinIME ($aosp) — GBoard not present on this image")
            }
            else -> {
                println("Warning: no usable soft keyboard IME found on this image — keyboard commands will be skipped. " +
                    "Prefer a google_apis_playstore image.")
            }
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
         * Return the system-image variant for a given API level. Plain `google_apis` (4 KB pages) is
         * published for every level we target (24–36) and boots reliably. The 16 KB page-size
         * (`google_apis_ps16k`) variant — previously forced for API 36 — exists only for 16 KB-page
         * compatibility testing and cold-boots far slower, which surfaced as API 36 provisioning
         * timeouts ("did not become install-ready"). `google_apis` for API 36 boots like every other.
         */
        @Suppress("UNUSED_PARAMETER")
        fun variantFor(api: Int): String = "google_apis"
    }
}
