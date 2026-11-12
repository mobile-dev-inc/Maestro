package util

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes swcd re-verify an app's `webcredentials` associated domains right after the
 * app is reinstalled on an iOS 27+ simulator.
 *
 * A reinstall gives the app a new persistent identifier, so swcd marks the app's
 * associated-domain entries stale. On iOS 27, an `ASWebAuthenticationSession` with an
 * HTTPS callback checks that entry and fails at once while it is stale ("Application
 * with identifier X is not associated with domain Y"), instead of waiting for swcd to
 * re-verify. The failed lookup makes swcd re-verify, so the app's second attempt works.
 * iOS 18 honors the stale entry and the first attempt works.
 *
 * Opening `https://<domain>/` makes swcd look the domain up, notice the stale entry and
 * download the domain's apple-app-site-association file again. We wait for that
 * download (swcd logs "Downloaded data for domain <domain>" at debug level), so the
 * app's first web login sees an approved entry.
 */
internal class AssociatedDomainsRefresher(
    private val openUrl: (deviceId: String, url: String) -> Unit,
    private val terminate: (deviceId: String, bundleId: String) -> Unit,
    private val startSwcdLogStream: (deviceId: String) -> Process = ::defaultSwcdLogStream,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val logger = LoggerFactory.getLogger(AssociatedDomainsRefresher::class.java)

    fun refresh(deviceId: String, bundleId: String, appBundle: Path, runtimeMajor: Int?) {
        if (runtimeMajor == null || runtimeMajor < MIN_IOS_MAJOR) return
        val domains = webCredentialsDomains(appBundle)
        if (domains.isEmpty()) return

        logger.info("Refreshing associated domains $domains for $bundleId")
        val downloaded = ConcurrentHashMap.newKeySet<String>()
        val logStream = startSwcdLogStream(deviceId)
        val reader = thread(isDaemon = true, name = "swcd-log-$deviceId") {
            runCatching {
                logStream.inputStream.bufferedReader().forEachLine { line ->
                    DOWNLOADED.find(line)?.let { downloaded.add(it.groupValues[1]) }
                }
            }
        }
        try {
            openUrl(deviceId, "https://${domains.first()}/")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!downloaded.containsAll(domains) && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
            }
            if (downloaded.containsAll(domains)) {
                logger.info("swcd re-verified $domains for $bundleId")
            } else {
                logger.warn("swcd did not re-verify ${domains - downloaded} for $bundleId within ${timeoutMs}ms")
            }
        } finally {
            logStream.destroy()
            logStream.waitFor(2, TimeUnit.SECONDS)
            reader.join(1_000)
            // The link may have opened the app (universal link) or Safari.
            runCatching { terminate(deviceId, bundleId) }
            runCatching { terminate(deviceId, SAFARI_BUNDLE_ID) }
        }
    }

    companion object {
        const val MIN_IOS_MAJOR = 27
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 200L
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        private val DOWNLOADED = Regex("""Downloaded data for domain (\S+)""")
        private val WEB_CREDENTIALS = Regex("""<string>\s*webcredentials:([^<?\s]+)""")
        private const val ENTITLEMENTS_KEY = "com.apple.developer.associated-domains"

        /**
         * The `webcredentials:` hosts from the app's entitlements. Simulator builds embed
         * entitlements as an XML plist inside the executable (the `__entitlements`
         * section), not in the code signature, so read the plist from the binary.
         */
        fun webCredentialsDomains(appBundle: Path): List<String> {
            val executable = executableName(appBundle) ?: return emptyList()
            val binary = appBundle.resolve(executable).toFile().takeIf { it.isFile } ?: return emptyList()
            val text = String(binary.readBytes(), Charsets.ISO_8859_1)
            val plist = Regex("""<plist[^>]*>.*?</plist>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .map { it.value }
                .firstOrNull { it.contains(ENTITLEMENTS_KEY) }
                ?: return emptyList()
            return WEB_CREDENTIALS.findAll(plist).map { it.groupValues[1] }.distinct().toList()
        }

        private fun executableName(appBundle: Path): String? {
            val infoPlist = appBundle.resolve("Info.plist").toFile()
            if (!infoPlist.isFile) return null
            val process = ProcessBuilder(
                "plutil", "-extract", "CFBundleExecutable", "raw", "-o", "-", infoPlist.absolutePath,
            ).start()
            val name = process.inputStream.bufferedReader().readText().trim()
            return if (process.waitFor() == 0 && name.isNotEmpty()) name else null
        }

        /** "com.apple.CoreSimulator.SimRuntime.iOS-27-2" -> 27. */
        fun runtimeMajor(runtimeIdentifier: String): Int? =
            Regex("""iOS-(\d+)""").find(runtimeIdentifier)?.groupValues?.get(1)?.toIntOrNull()

        private fun defaultSwcdLogStream(deviceId: String): Process =
            ProcessBuilder(
                "xcrun", "simctl", "spawn", deviceId, "log", "stream", "--level", "debug", "--style", "compact",
                "--predicate", "process == \"swcd\" AND eventMessage CONTAINS \"Downloaded data for domain\"",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
}
