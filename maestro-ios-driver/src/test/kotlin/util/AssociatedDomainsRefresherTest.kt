package util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AssociatedDomainsRefresherTest {

    @TempDir
    lateinit var tempDir: Path

    private val calls = mutableListOf<String>()
    private var runtime = "27.2"

    private val refresher = AssociatedDomainsRefresher(
        runtimeVersion = { calls += "runtime"; runtime },
        openUrl = { _, url -> calls += "open $url" },
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
        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(fakeApp())).isEmpty()
    }

    @Test
    fun `on iOS 27 opens every domain, closes the app and Safari, then waits`() {
        val app = fakeApp("webcredentials:example.com", "webcredentials:login.example.com")

        refresher.refresh("UDID", "com.example", app)
        refresher.refresh("UDID", "com.example", app)

        val oneRefresh = listOf(
            "open https://example.com/__maestro_swc_refresh",
            "open https://login.example.com/__maestro_swc_refresh",
            "terminate com.example",
            "terminate com.apple.mobilesafari",
            "wait",
        )
        // The runtime is looked up once per device.
        assertThat(calls).containsExactlyElementsIn(listOf("runtime") + oneRefresh + oneRefresh).inOrder()
    }

    @Test
    fun `does nothing before iOS 27 or without webcredentials domains`() {
        refresher.refresh("UDID-27", "com.example", fakeApp("applinks:example.com"))
        runtime = "26.2"
        refresher.refresh("UDID-26", "com.example", fakeApp("webcredentials:example.com"))

        assertThat(calls).containsExactly("runtime", "runtime")
    }

    // A minimal .app whose executable is a Mach-O file containing an entitlements plist, the way
    // simulator builds embed it, next to a non-Mach-O file that must be ignored.
    private fun fakeApp(vararg associatedDomains: String): Path {
        val app = tempDir.resolve("Example${System.nanoTime()}.app").toFile().apply { mkdirs() }
        app.resolve("Info.plist").writeText("<plist><dict><key>com.apple.developer.associated-domains</key></dict></plist>")
        val entitlements = if (associatedDomains.isEmpty()) "<plist version=\"1.0\"><dict/></plist>" else
            "<plist version=\"1.0\"><dict><key>com.apple.developer.associated-domains</key><array>" +
                associatedDomains.joinToString("") { "<string>$it</string>" } + "</array></dict></plist>"
        val machOMagic = byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())
        app.resolve("Example").writeBytes(machOMagic + entitlements.toByteArray() + byteArrayOf(0))
        return app.toPath()
    }
}
