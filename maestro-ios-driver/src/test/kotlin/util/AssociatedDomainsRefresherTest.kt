package util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AssociatedDomainsRefresherTest {

    @TempDir
    lateinit var tempDir: Path

    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var runtime: String? = "27.2"

    private val refresher = AssociatedDomainsRefresher(
        runtimeVersion = { calls += "runtime"; runtime },
        openUrl = { _, url ->
            calls += "open $url"
            if ("fails" in url) throw IllegalStateException("openurl failed")
        },
        terminate = { _, bundleId -> calls += "terminate $bundleId" },
        wait = { calls += "wait" },
    )

    @Test
    fun `reads webcredentials hosts from the Mach-O executable's embedded entitlements`() {
        val app = fakeApp(
            "applinks:example.com",
            "webcredentials:example.com",
            "webcredentials:*.example.com",
            "webcredentials:www.example.com?mode=developer",
        )

        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(app)).containsExactly("example.com", "www.example.com").inOrder()
        // Chunks smaller than a match, in a file larger than the 1 KiB overlap, split every match across reads.
        for (chunkSize in listOf(7, 33, 700)) {
            assertThat(AssociatedDomainsRefresher.webCredentialsDomains(app, chunkSize)).containsExactly("example.com", "www.example.com").inOrder()
        }
        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(fakeApp())).isEmpty()
    }

    @Test
    fun `detects the HTTPS callback API in the executable or an embedded framework or dylib`() {
        val inExecutable = fakeApp("webcredentials:example.com")
        val inFramework = fakeApp("webcredentials:example.com", httpsCallbackIn = "Frameworks/Auth.framework/Auth")
        val inDylib = fakeApp("webcredentials:example.com", httpsCallbackIn = "Frameworks/libAuth.dylib")
        // Every executable also starts a session (with a custom-scheme callback), which the bug doesn't affect.
        val customSchemeOnly = fakeApp("webcredentials:example.com", httpsCallbackIn = null)

        for (chunkSize in listOf(7, 33, 1 shl 20)) {
            assertThat(AssociatedDomainsRefresher.usesHttpsCallbacks(inExecutable, chunkSize)).isTrue()
            assertThat(AssociatedDomainsRefresher.usesHttpsCallbacks(inFramework, chunkSize)).isTrue()
            assertThat(AssociatedDomainsRefresher.usesHttpsCallbacks(inDylib, chunkSize)).isTrue()
            // Info.plist mentions the API too, but only Mach-O files count.
            assertThat(AssociatedDomainsRefresher.usesHttpsCallbacks(customSchemeOnly, chunkSize)).isFalse()
        }
    }

    @Test
    fun `on iOS 27 opens the first domain alone, then the rest up to 8 at a time, then closes the app and Safari around the wait`() {
        val others = (1..9).map { if (it == 1) "fails$it.example.com" else "d$it.example.com" }
        val app = fakeApp(*(listOf("first.example.com") + others).map { "webcredentials:$it" }.toTypedArray())
        // The other opens wait until 8 of them run at once, so this only completes if they run in parallel.
        val eightOpening = CountDownLatch(8)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        var othersStartedDuringFirst = false
        val refresher = AssociatedDomainsRefresher(
            runtimeVersion = { calls += "runtime"; "27.2" },
            openUrl = { _, url ->
                if ("first" in url) {
                    Thread.sleep(200)
                    othersStartedDuringFirst = eightOpening.count < 8
                } else {
                    maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    eightOpening.countDown()
                    check(eightOpening.await(10, TimeUnit.SECONDS)) { "opens did not run in parallel" }
                    Thread.sleep(100) // Room for a ninth open to start, if there were no cap.
                    active.decrementAndGet()
                }
                calls += "open $url"
                if ("fails" in url) throw IllegalStateException("openurl failed")
            },
            terminate = { _, bundleId -> calls += "terminate $bundleId" },
            wait = { calls += "wait" },
        )

        refresher.refresh("UDID", "com.example", app)

        assertThat(othersStartedDuringFirst).isFalse()
        assertThat(maxActive.get()).isEqualTo(8)
        val close = listOf("terminate com.example", "terminate com.apple.mobilesafari")
        assertThat(calls.take(2)).containsExactly("runtime", "open https://first.example.com/__maestro_swc_refresh").inOrder()
        assertThat(calls.subList(2, 11)).containsExactlyElementsIn(others.map { "open https://$it/__maestro_swc_refresh" })
        assertThat(calls.drop(11)).containsExactlyElementsIn(close + "wait" + close).inOrder()

        // The runtime is looked up once per device.
        calls.clear()
        refresher.refresh("UDID", "com.example", fakeApp("webcredentials:login.example.com"))
        assertThat(calls).doesNotContain("runtime")
    }

    @Test
    fun `does nothing before iOS 27, without webcredentials domains or HTTPS callbacks, or when the runtime is unknown`() {
        refresher.refresh("UDID-27", "com.example", fakeApp("applinks:example.com"))
        // webcredentials used only for password autofill: the app never starts an HTTPS-callback session.
        refresher.refresh("UDID-27", "com.example", fakeApp("webcredentials:example.com", httpsCallbackIn = null))
        runtime = "26.2"
        refresher.refresh("UDID-26", "com.example", fakeApp("webcredentials:example.com"))
        // An unknown runtime is not cached: the next refresh looks it up again.
        runtime = null
        refresher.refresh("UDID-X", "com.example", fakeApp("webcredentials:example.com"))
        refresher.refresh("UDID-X", "com.example", fakeApp("webcredentials:example.com"))

        assertThat(calls).containsExactly("runtime", "runtime", "runtime", "runtime")
    }

    // A minimal .app whose executable is a Mach-O file containing an entitlements plist, the way
    // simulator builds embed it, next to a non-Mach-O file that must be ignored. The HTTPS-callback
    // selector goes in the Mach-O file at [httpsCallbackIn], or nowhere when it's null.
    private fun fakeApp(vararg associatedDomains: String, httpsCallbackIn: String? = "Example"): Path {
        val app = tempDir.resolve("Example${System.nanoTime()}.app").toFile().apply { mkdirs() }
        app.resolve("Info.plist").writeText("<plist><dict><key>com.apple.developer.associated-domains</key><string>$HTTPS_CALLBACK_SELECTOR</string></dict></plist>")
        val entitlements = if (associatedDomains.isEmpty()) "<plist version=\"1.0\"><dict/></plist>" else
            "<plist version=\"1.0\"><dict><key>com.apple.developer.associated-domains</key><array>" +
                associatedDomains.joinToString("") { "<string>$it</string>" } + "</array></dict></plist>"
        // A stray copy of the entitlement key before the real entitlements, as a string literal could be.
        val stray = "com.apple.developer.associated-domains\u0000".toByteArray()
        app.resolve("Example").writeBytes(MACH_O + ByteArray(2_000) + stray + entitlements.toByteArray() + "\u0000$SESSION_SELECTOR\u0000".toByteArray())
        app.resolve("Frameworks/Auth.framework").mkdirs()
        app.resolve("Frameworks/Auth.framework/Auth").writeBytes(MACH_O + ByteArray(2_000))
        app.resolve("Frameworks/libAuth.dylib").writeBytes(MACH_O + ByteArray(2_000))
        if (httpsCallbackIn != null) app.resolve(httpsCallbackIn).appendBytes("$HTTPS_CALLBACK_SELECTOR\u0000".toByteArray())
        return app.toPath()
    }

    private companion object {
        // How every HTTPS callback is made, from Swift (`.https(host:path:)`), Objective-C or SwiftUI.
        const val HTTPS_CALLBACK_SELECTOR = "callbackWithHTTPSHost:path:"
        // Starts a session with any callback, including custom URL schemes.
        const val SESSION_SELECTOR = "initWithURL:callback:completionHandler:"
        val MACH_O = byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())
    }
}
