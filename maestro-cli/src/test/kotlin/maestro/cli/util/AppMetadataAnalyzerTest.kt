package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AppMetadataAnalyzerTest {

    @TempDir
    lateinit var tempDir: File

    // Minimal XML plist with the required keys for iOS detection
    private fun iosPlistXml(
        bundleId: String = "com.example.app",
        platformName: String = "iphonesimulator",
        minimumOSVersion: String = "16.0",
        bundleName: String = "ExampleApp",
    ) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>$bundleId</string>
    <key>CFBundleName</key><string>$bundleName</string>
    <key>DTPlatformName</key><string>$platformName</string>
    <key>MinimumOSVersion</key><string>$minimumOSVersion</string>
</dict>
</plist>""".toByteArray()

    private fun makeIosZip(entryPath: String = "Payload/ExampleApp.app/Info.plist"): File {
        val zip = File(tempDir, "app.ipa")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entryPath))
            zos.write(iosPlistXml())
            zos.closeEntry()
        }
        return zip
    }

    private fun makeWebJson(url: String = "https://example.com"): File {
        return File(tempDir, "web.json").also { it.writeText("""{"url":"$url"}""") }
    }

    private fun makeUnknownFile(): File {
        return File(tempDir, "unknown.bin").also { it.writeBytes(ByteArray(64) { i -> i.toByte() }) }
    }

    // ---- getIosAppMetadata ----

    @Test
    fun `getIosAppMetadata extracts bundleId and platformName from plist in zip`() {
        val result = AppMetadataAnalyzer.getIosAppMetadata(makeIosZip())

        assertThat(result).isNotNull()
        assertThat(result!!.bundleId).isEqualTo("com.example.app")
        assertThat(result.platformName).isEqualTo("iphonesimulator")
        assertThat(result.minimumOSVersion).isEqualTo("16.0")
    }

    @Test
    fun `getIosAppMetadata ignores Watch bundle Info plist`() {
        val zip = File(tempDir, "app_watch.ipa")
        ZipOutputStream(zip.outputStream()).use { zos ->
            // Real app plist at depth 2
            zos.putNextEntry(ZipEntry("Payload/App.app/Info.plist"))
            zos.write(iosPlistXml(bundleId = "com.real.app"))
            zos.closeEntry()
            // Watch plist — should be ignored
            zos.putNextEntry(ZipEntry("Payload/App.app/Watch/Companion.app/Info.plist"))
            zos.write(iosPlistXml(bundleId = "com.watch.app"))
            zos.closeEntry()
        }
        val result = AppMetadataAnalyzer.getIosAppMetadata(zip)
        assertThat(result!!.bundleId).isEqualTo("com.real.app")
    }

    @Test
    fun `getIosAppMetadata returns null for non-zip file`() {
        assertThat(AppMetadataAnalyzer.getIosAppMetadata(makeUnknownFile())).isNull()
    }

    // ---- getWebMetadata ----

    @Test
    fun `getWebMetadata returns url from JSON file`() {
        val result = AppMetadataAnalyzer.getWebMetadata(makeWebJson("https://example.com"))
        assertThat(result!!.url).isEqualTo("https://example.com")
    }

    @Test
    fun `getWebMetadata returns null for non-JSON file`() {
        assertThat(AppMetadataAnalyzer.getWebMetadata(makeUnknownFile())).isNull()
    }

    @Test
    fun `getIosAppMetadata picks shallowest plist when multiple app bundles present`() {
        val zip = File(tempDir, "multi.ipa")
        ZipOutputStream(zip.outputStream()).use { zos ->
            // Deeper entry first
            zos.putNextEntry(ZipEntry("Payload/Outer.app/Inner.app/Info.plist"))
            zos.write(iosPlistXml(bundleId = "com.inner.app"))
            zos.closeEntry()
            // Shallower entry — this should win
            zos.putNextEntry(ZipEntry("Payload/Outer.app/Info.plist"))
            zos.write(iosPlistXml(bundleId = "com.outer.app"))
            zos.closeEntry()
        }
        val result = AppMetadataAnalyzer.getIosAppMetadata(zip)
        assertThat(result!!.bundleId).isEqualTo("com.outer.app")
    }
}
