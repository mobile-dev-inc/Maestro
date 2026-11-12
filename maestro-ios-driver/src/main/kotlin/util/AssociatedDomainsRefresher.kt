package util

import java.nio.file.Path

/**
 * On iOS 27+, reinstalling an app (what clearState does) leaves swcd's record of the app's
 * associated domains stale, and ASWebAuthenticationSession rejects HTTPS callbacks with
 * "Application with identifier X is not associated with domain Y" until swcd re-verifies the
 * domain. Opening one of the app's webcredentials domains makes swcd re-verify it.
 */
internal class AssociatedDomainsRefresher(
    private val openUrl: (deviceId: String, url: String) -> Unit,
    private val terminate: (deviceId: String, bundleId: String) -> Unit,
    private val wait: () -> Unit = { Thread.sleep(5_000) },
) {

    fun refresh(deviceId: String, bundleId: String, appBundle: Path, runtimeId: String?) {
        val major = runtimeId?.let { Regex("""iOS-(\d+)""").find(it) }?.groupValues?.get(1)?.toInt() ?: return
        if (major < 27) return
        val domain = webCredentialsDomains(appBundle).firstOrNull() ?: return
        openUrl(deviceId, "https://$domain/")
        wait()
        // The link opens Safari, or the app if its universal links claim the path.
        terminate(deviceId, bundleId)
        terminate(deviceId, "com.apple.mobilesafari")
    }

    companion object {
        /**
         * Simulator builds embed entitlements as an XML plist inside the executable, not in the
         * code signature, so read them from the binary.
         */
        fun webCredentialsDomains(appBundle: Path): List<String> {
            val process = ProcessBuilder(
                "plutil", "-extract", "CFBundleExecutable", "raw", "-o", "-", appBundle.resolve("Info.plist").toString(),
            ).start()
            val executable = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0) return emptyList()
            val binary = String(appBundle.resolve(executable).toFile().readBytes(), Charsets.ISO_8859_1)
            val entitlements = Regex("""<plist[^>]*>.*?</plist>""", RegexOption.DOT_MATCHES_ALL).findAll(binary)
                .map { it.value }
                .firstOrNull { "com.apple.developer.associated-domains" in it } ?: return emptyList()
            return Regex("""<string>\s*webcredentials:([^<?\s]+)""").findAll(entitlements).map { it.groupValues[1] }.toList()
        }
    }
}
