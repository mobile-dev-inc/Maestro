package util

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On iOS 27+, reinstalling an app (what clearState does) leaves swcd's record of the app's
 * associated domains stale, and ASWebAuthenticationSession rejects HTTPS callbacks with
 * "Application with identifier X is not associated with domain Y" until swcd re-verifies the
 * domain. Opening a URL on the domain makes swcd re-verify it.
 *
 * Works around an iOS 27 platform bug reported to Apple as FB25544474. Remove once a fixed iOS
 * release is the oldest one this applies to.
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
        if (domains.isEmpty() || !usesHttpsCallbacks(appBundle)) return

        // Opening the URL is what triggers re-verification. Close Safari, or the app if its
        // universal links claim the path, right away, and again after the wait in case either
        // started late, so neither is left running for the flow.
        openAll(deviceId, domains)
        closeAppAndSafari(deviceId, bundleId)
        wait()
        closeAppAndSafari(deviceId, bundleId)
    }

    // Apps can list dozens of domains, so open a few at a time; swcd downloads them in parallel.
    // The first one opens alone so Safari is up before the rest arrive.
    private fun openAll(deviceId: String, domains: List<String>) {
        open(deviceId, domains.first())
        val rest = domains.drop(1)
        if (rest.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(rest.size, MAX_PARALLEL_OPENS))
        try {
            pool.invokeAll(rest.map { domain -> Callable { open(deviceId, domain) } })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun open(deviceId: String, domain: String) =
        ignoringErrors("open $domain") { openUrl(deviceId, "https://$domain$REFRESH_PATH") }

    // Cache only a successful lookup, so a transient simctl failure doesn't disable the refresh.
    private fun runtimeMajor(deviceId: String): Int =
        runtimeMajors[deviceId] ?: runtimeVersion(deviceId)?.substringBefore('.')?.toIntOrNull()
            ?.also { runtimeMajors[deviceId] = it } ?: 0

    private fun closeAppAndSafari(deviceId: String, bundleId: String) {
        ignoringErrors("terminate $bundleId") { terminate(deviceId, bundleId) }
        ignoringErrors("terminate Safari") { terminate(deviceId, SAFARI_BUNDLE_ID) }
    }

    // Best effort, except that cancellation must still propagate.
    private fun ignoringErrors(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Associated domains refresh: could not $action", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AssociatedDomainsRefresher::class.java)
        private const val REFRESH_PATH = "/__maestro_swc_refresh"
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        // The host must be followed by "?" or "<", so a match cut off at a chunk end is not taken.
        private val WEB_CREDENTIALS = Regex("""<string>\s{0,64}webcredentials:([^<?\s]{1,253})(?=[?<])""")
        // The selector that makes an HTTPS callback (iOS 17.4+), the only kind this bug breaks, from
        // Swift's `.https(host:path:)`, Objective-C or SwiftUI. It is in whichever binary makes the call.
        private val HTTPS_CALLBACK = Regex("""(callbackWithHTTPSHost:path:)""")
        private const val OVERLAP = 1024
        private const val MAX_PARALLEL_OPENS = 8
        private val MACH_O_MAGICS = setOf(0xFEEDFACF, 0xCFFAEDFE, 0xCAFEBABE, 0xBEBAFECA, 0xCAFEBABF, 0xBFBAFECA)

        /**
         * The app's `webcredentials:` hosts. Simulator builds embed the entitlements as an XML plist
         * in the executable (`codesign` doesn't report them), so scan the Mach-O files at the top of
         * the bundle. Wildcard entries are skipped: they have no host to open.
         */
        fun webCredentialsDomains(appBundle: Path, chunkSize: Int = 1 shl 20): List<String> =
            machOFiles(appBundle.toFile())
                .flatMap { scan(it, chunkSize, WEB_CREDENTIALS) }
                .filterNot { it.startsWith("*.") }
                .distinct()

        /**
         * Whether the app, or a framework it embeds (login SDKs often are), can start a session with
         * an HTTPS callback. Apps that list `webcredentials` only for password autofill can't.
         */
        fun usesHttpsCallbacks(appBundle: Path, chunkSize: Int = 1 shl 20): Boolean {
            val frameworksDir = appBundle.resolve("Frameworks").toFile()
            val frameworks = frameworksDir.listFiles().orEmpty().filter { it.isDirectory }
            return (listOf(appBundle.toFile(), frameworksDir) + frameworks)
                .asSequence()
                .flatMap(::machOFiles)
                .any { scan(it, chunkSize, HTTPS_CALLBACK, firstOnly = true).isNotEmpty() }
        }

        private fun machOFiles(dir: File): List<File> = dir.listFiles().orEmpty().filter { it.isFile && isMachO(it) }

        // Reads in chunks so large executables aren't loaded whole. Each chunk is searched together
        // with the end of the previous one, so a match split across chunks is still found.
        private fun scan(file: File, chunkSize: Int, pattern: Regex, firstOnly: Boolean = false): List<String> {
            require(chunkSize > 0)
            val found = mutableListOf<String>()
            val chunk = ByteArray(chunkSize)
            var tail = ""
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) return found
                    val text = tail + String(chunk, 0, read, Charsets.ISO_8859_1)
                    pattern.findAll(text).forEach { found += it.groupValues[1] }
                    if (firstOnly && found.isNotEmpty()) return found
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
