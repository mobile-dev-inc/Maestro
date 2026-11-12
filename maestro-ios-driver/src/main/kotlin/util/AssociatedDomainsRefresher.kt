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
        val major = runtimeMajors.getOrPut(deviceId) {
            runtimeVersion(deviceId)?.substringBefore('.')?.toIntOrNull() ?: 0
        }
        if (major < 27) return
        val domains = webCredentialsDomains(appBundle)
        if (domains.isEmpty()) return

        // The lookup that triggers re-verification happens when the URL is opened. Close
        // Safari, or the app if its universal links claim the path, right away so neither
        // keeps running; swcd re-verifies on its own during the wait.
        domains.forEach { openUrl(deviceId, "https://$it$REFRESH_PATH") }
        runCatching { terminate(deviceId, bundleId) }
        runCatching { terminate(deviceId, SAFARI_BUNDLE_ID) }
        wait()
    }

    companion object {
        private const val REFRESH_PATH = "/__maestro_swc_refresh"
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        private val MACH_O_MAGICS = setOf(0xFEEDFACF, 0xCFFAEDFE, 0xCAFEBABE, 0xBEBAFECA)

        /**
         * The app's `webcredentials:` hosts. Simulator builds embed entitlements as an XML plist
         * inside the executable, not in the code signature, so read them from the Mach-O files
         * at the top of the bundle. Wildcard entries are skipped: they have no host to open.
         */
        fun webCredentialsDomains(appBundle: Path): List<String> {
            val entitlements = appBundle.toFile().listFiles().orEmpty()
                .filter { it.isFile && isMachO(it) }
                .firstNotNullOfOrNull { file ->
                    Regex("""<plist[^>]*>.*?</plist>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(String(file.readBytes(), Charsets.ISO_8859_1))
                        .map { it.value }
                        .firstOrNull { "com.apple.developer.associated-domains" in it }
                } ?: return emptyList()
            return Regex("""<string>\s*webcredentials:([^<?\s]+)""").findAll(entitlements)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("*.") }
                .distinct()
                .toList()
        }

        private fun isMachO(file: File): Boolean {
            val header = file.inputStream().use { it.readNBytes(4) }
            if (header.size < 4) return false
            val magic = header.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            return magic in MACH_O_MAGICS
        }
    }
}
