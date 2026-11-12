package util

import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * On iOS 27+, reinstalling an app (what clearState does) leaves swcd's record of the app's
 * associated domains stale, and ASWebAuthenticationSession rejects HTTPS callbacks with
 * "Application with identifier X is not associated with domain Y" until swcd re-verifies the
 * domain. Opening a URL on the domain makes swcd re-verify it.
 */
internal class AssociatedDomainsRefresher(
    private val runtimeVersion: (deviceId: String) -> String?,
    private val openUrl: (deviceId: String, url: String) -> Unit,
    private val terminate: (deviceId: String, bundleId: String) -> Unit,
    private val wait: () -> Unit = { Thread.sleep(5_000) },
) {
    private val runtimeMajors = ConcurrentHashMap<String, Int>()

    fun refresh(deviceId: String, bundleId: String, appBundle: Path) {
        if (runtimeMajor(deviceId) < 27) return
        val domains = webCredentialsDomains(appBundle)
        if (domains.isEmpty()) return

        // Opening the URL is what triggers re-verification. Close Safari, or the app if its
        // universal links claim the path, right away, and again after the wait in case either
        // started late, so neither is left running for the flow.
        domains.forEach { domain -> ignoringErrors { openUrl(deviceId, "https://$domain$REFRESH_PATH") } }
        closeAppAndSafari(deviceId, bundleId)
        wait()
        closeAppAndSafari(deviceId, bundleId)
    }

    // Cache only a successful lookup, so a transient simctl failure doesn't disable the refresh.
    private fun runtimeMajor(deviceId: String): Int =
        runtimeMajors[deviceId] ?: runtimeVersion(deviceId)?.substringBefore('.')?.toIntOrNull()
            ?.also { runtimeMajors[deviceId] = it } ?: 0

    private fun closeAppAndSafari(deviceId: String, bundleId: String) {
        ignoringErrors { terminate(deviceId, bundleId) }
        ignoringErrors { terminate(deviceId, SAFARI_BUNDLE_ID) }
    }

    // Best effort, except that cancellation must still propagate.
    private fun ignoringErrors(block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val REFRESH_PATH = "/__maestro_swc_refresh"
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        // The host must be followed by "?" or "<", so a match cut off at a chunk end is not taken.
        private val WEB_CREDENTIALS = Regex("""<string>\s*webcredentials:([^<?\s]{1,253})(?=[?<])""")
        private const val OVERLAP = 1024
        private val MACH_O_MAGICS = setOf(0xFEEDFACF, 0xCFFAEDFE, 0xCAFEBABE, 0xBEBAFECA)

        /**
         * The app's `webcredentials:` hosts. Simulator builds embed entitlements as an XML plist
         * inside the executable, not in the code signature, so scan the Mach-O files at the top of
         * the bundle. Wildcard entries are skipped: they have no host to open.
         */
        fun webCredentialsDomains(appBundle: Path, chunkSize: Int = 1 shl 20): List<String> =
            appBundle.toFile().listFiles().orEmpty()
                .filter { it.isFile && isMachO(it) }
                .flatMap { scan(it, chunkSize) }
                .filterNot { it.startsWith("*.") }
                .distinct()

        // Reads in chunks so large executables aren't loaded whole. Each chunk is searched together
        // with the end of the previous one, so a match split across chunks is still found.
        private fun scan(file: File, chunkSize: Int): List<String> {
            val found = mutableListOf<String>()
            val chunk = ByteArray(chunkSize)
            var tail = ""
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) return found
                    val text = tail + String(chunk, 0, read, Charsets.ISO_8859_1)
                    WEB_CREDENTIALS.findAll(text).forEach { found += it.groupValues[1] }
                    tail = text.takeLast(OVERLAP)
                }
            }
        }

        private fun isMachO(file: File): Boolean {
            val header = file.inputStream().use { it.readNBytes(4) }
            if (header.size < 4) return false
            val magic = header.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            return magic in MACH_O_MAGICS
        }
    }
}
