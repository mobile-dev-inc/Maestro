package util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AssociatedDomainsRefresherTest {

    @TempDir
    lateinit var tempDir: Path

    private val calls = mutableListOf<String>()
    private val refresher = AssociatedDomainsRefresher(
        openUrl = { _, url -> calls += "open $url" },
        terminate = { _, bundleId -> calls += "terminate $bundleId" },
        wait = { calls += "wait" },
    )

    @Test
    fun `reads only webcredentials hosts from the entitlements embedded in the executable`() {
        val app = fakeApp("applinks:example.com", "webcredentials:example.com", "webcredentials:www.example.com?mode=developer")

        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(app)).containsExactly("example.com", "www.example.com").inOrder()
        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(fakeApp())).isEmpty()
    }

    @Test
    fun `on iOS 27 opens the first domain, waits, then closes the app and Safari`() {
        refresher.refresh("UDID", "com.example", fakeApp("webcredentials:example.com"), "com.apple.CoreSimulator.SimRuntime.iOS-27-2")

        assertThat(calls).containsExactly("open https://example.com/", "wait", "terminate com.example", "terminate com.apple.mobilesafari").inOrder()
    }

    @Test
    fun `does nothing before iOS 27 or without webcredentials domains`() {
        refresher.refresh("UDID", "com.example", fakeApp("webcredentials:example.com"), "com.apple.CoreSimulator.SimRuntime.iOS-18-2")
        refresher.refresh("UDID", "com.example", fakeApp("applinks:example.com"), "com.apple.CoreSimulator.SimRuntime.iOS-27-2")

        assertThat(calls).isEmpty()
    }

    // A minimal .app: Info.plist naming the executable, and an executable whose bytes contain an
    // entitlements plist, the way simulator builds embed it.
    private fun fakeApp(vararg associatedDomains: String): Path {
        val app = tempDir.resolve("Example.app").toFile().apply { mkdirs() }
        app.resolve("Info.plist").writeText(
            """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleExecutable</key><string>Example</string></dict></plist>"""
        )
        val entitlements = if (associatedDomains.isEmpty()) "<plist version=\"1.0\"><dict/></plist>" else
            "<plist version=\"1.0\"><dict><key>com.apple.developer.associated-domains</key><array>" +
                associatedDomains.joinToString("") { "<string>$it</string>" } + "</array></dict></plist>"
        app.resolve("Example").writeBytes(byteArrayOf(0, 1) + entitlements.toByteArray() + byteArrayOf(2))
        return app.toPath()
    }
}
