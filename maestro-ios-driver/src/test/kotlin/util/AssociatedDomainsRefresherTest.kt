package util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path

class AssociatedDomainsRefresherTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reads webcredentials hosts from the entitlements embedded in the executable`() {
        val app = fakeApp(
            "webcredentials:example.com",
            "applinks:example.com",
            "webcredentials:www.example.com?mode=developer",
        )

        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(app))
            .containsExactly("example.com", "www.example.com").inOrder()
    }

    @Test
    fun `no associated domains means nothing to refresh`() {
        assertThat(AssociatedDomainsRefresher.webCredentialsDomains(fakeApp())).isEmpty()
    }

    @Test
    fun `parses the iOS major from a runtime identifier`() {
        assertThat(AssociatedDomainsRefresher.runtimeMajor("com.apple.CoreSimulator.SimRuntime.iOS-27-2")).isEqualTo(27)
        assertThat(AssociatedDomainsRefresher.runtimeMajor("com.apple.CoreSimulator.SimRuntime.iOS-18-2")).isEqualTo(18)
        assertThat(AssociatedDomainsRefresher.runtimeMajor("com.apple.CoreSimulator.SimRuntime.watchOS-11-0")).isNull()
    }

    @Test
    fun `does nothing before iOS 27`() {
        val opened = mutableListOf<String>()
        refresher(opened, logLines = emptyList()).refresh("UDID", "com.example", fakeApp("webcredentials:example.com"), 18)

        assertThat(opened).isEmpty()
    }

    @Test
    fun `opens the first domain and returns once swcd has downloaded every domain`() {
        val opened = mutableListOf<String>()
        val terminated = mutableListOf<String>()
        val app = fakeApp("webcredentials:example.com", "webcredentials:www.example.com")
        val start = System.currentTimeMillis()

        refresher(
            opened,
            terminated,
            logLines = listOf(
                "swcd: [com.apple.swc:dl] Downloaded data for domain www.example.com (route? cdn)",
                "swcd: [com.apple.swc:dl] Downloaded data for domain example.com (route? cdn)",
            ),
        ).refresh("UDID", "com.example", app, 27)

        assertThat(opened).containsExactly("https://example.com/")
        assertThat(terminated).containsExactly("com.example", "com.apple.mobilesafari")
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000)
    }

    @Test
    fun `gives up after the timeout when swcd never downloads`() {
        val terminated = mutableListOf<String>()
        val start = System.currentTimeMillis()

        refresher(mutableListOf(), terminated, logLines = emptyList(), timeoutMs = 500)
            .refresh("UDID", "com.example", fakeApp("webcredentials:example.com"), 27)

        assertThat(System.currentTimeMillis() - start).isAtLeast(500)
        assertThat(terminated).containsExactly("com.example", "com.apple.mobilesafari")
    }

    private fun refresher(
        opened: MutableList<String>,
        terminated: MutableList<String> = mutableListOf(),
        logLines: List<String>,
        timeoutMs: Long = 5_000,
    ) = AssociatedDomainsRefresher(
        openUrl = { _, url -> opened += url },
        terminate = { _, bundleId -> terminated += bundleId },
        startSwcdLogStream = { fakeProcess(logLines) },
        timeoutMs = timeoutMs,
    )

    private fun fakeProcess(lines: List<String>): Process {
        val out: InputStream = ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray())
        return mockk(relaxed = true) {
            every { inputStream } returns out
        }
    }

    // A minimal .app: Info.plist naming the executable, and an executable whose bytes
    // contain an entitlements plist, the way simulator builds embed it.
    private fun fakeApp(vararg associatedDomains: String): Path {
        val app = tempDir.resolve("Example.app").toFile().apply { mkdirs() }
        app.resolve("Info.plist").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<plist version="1.0"><dict><key>CFBundleExecutable</key><string>Example</string></dict></plist>
            """.trimMargin()
        )
        val entitlements = if (associatedDomains.isEmpty()) "<plist version=\"1.0\"><dict/></plist>" else
            "<plist version=\"1.0\"><dict><key>com.apple.developer.associated-domains</key><array>" +
                associatedDomains.joinToString("") { "<string>$it</string>" } + "</array></dict></plist>"
        app.resolve("Example").writeBytes(byteArrayOf(0, 1, 2) + entitlements.toByteArray() + byteArrayOf(3, 4))
        return app.toPath()
    }
}
