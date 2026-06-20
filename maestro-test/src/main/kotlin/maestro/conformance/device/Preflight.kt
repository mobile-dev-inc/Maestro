package maestro.conformance.device

object Preflight {
    private val REQUIRED = listOf("adb", "avdmanager", "emulator", "sdkmanager")

    fun check() {
        val missing = REQUIRED.filter { tool ->
            Cmd.run("/bin/sh", "-c", "command -v $tool").exit != 0
        }
        require(missing.isEmpty()) {
            "Missing Android SDK tools on PATH: ${missing.joinToString()}. " +
                "Install cmdline-tools + platform-tools and ensure they're on PATH."
        }
    }
}
