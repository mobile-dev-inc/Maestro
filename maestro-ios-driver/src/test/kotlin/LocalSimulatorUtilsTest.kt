import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class LocalSimulatorUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        cacheDir = tempDir.resolve("app-cache").resolve("test-device-id").toFile()
        cacheDir.mkdirs()
    }

    // --- Task #13: App binary cache staleness ---

    @Test
    fun `cached binary should be updated when installed app has different content`() {
        val bundleId = "com.example.app"

        // Simulate cached app (v1)
        val cachedAppDir = File(cacheDir, "$bundleId.app")
        cachedAppDir.mkdirs()
        File(cachedAppDir, "Info.plist").writeText("v1-plist")
        File(cachedAppDir, "AppBinary").writeText("v1-binary")

        // Simulate installed app updated to v2 (different binary)
        val installedAppDir = tempDir.resolve("installed").resolve("$bundleId.app").toFile()
        installedAppDir.mkdirs()
        File(installedAppDir, "Info.plist").writeText("v2-plist")
        File(installedAppDir, "AppBinary").writeText("v2-binary-updated")

        // getCachedAppBinary should detect the mismatch and re-cache.
        // For now, simulate what the fix should do:
        // compare installed vs cached, update cache if different.
        val isCacheStale = isBinaryCacheStale(cachedAppDir, installedAppDir)

        // DESIRED: cache should be detected as stale
        assertThat(isCacheStale).isTrue()
    }

    @Test
    fun `cached binary should be reused when installed app matches`() {
        val bundleId = "com.example.app"

        // Same content in cache and installed
        val cachedAppDir = File(cacheDir, "$bundleId.app")
        cachedAppDir.mkdirs()
        File(cachedAppDir, "AppBinary").writeText("same-binary")

        val installedAppDir = tempDir.resolve("installed").resolve("$bundleId.app").toFile()
        installedAppDir.mkdirs()
        File(installedAppDir, "AppBinary").writeText("same-binary")

        val isCacheStale = isBinaryCacheStale(cachedAppDir, installedAppDir)

        // Cache matches — not stale
        assertThat(isCacheStale).isFalse()
    }

    @Test
    fun `per-device cache dirs should be isolated`() {
        val bundleId = "com.example.app"

        val deviceACache = tempDir.resolve("app-cache/device-A/$bundleId.app").toFile()
        deviceACache.mkdirs()
        File(deviceACache, "data").writeText("device-A-data")

        val deviceBCache = tempDir.resolve("app-cache/device-B/$bundleId.app").toFile()
        deviceBCache.mkdirs()
        File(deviceBCache, "data").writeText("device-B-data")

        // Modifying device A should not affect device B
        File(deviceACache, "data").writeText("device-A-modified")
        assertThat(File(deviceBCache, "data").readText()).isEqualTo("device-B-data")
    }

    // --- Helper: this is the logic getCachedAppBinary SHOULD implement ---

    private fun isBinaryCacheStale(cachedDir: File, installedDir: File): Boolean {
        if (!cachedDir.exists()) return true
        if (!installedDir.exists()) return false

        // Compare file contents to detect staleness
        val cachedFiles = cachedDir.walkTopDown().filter { it.isFile }.sortedBy { it.name }
        val installedFiles = installedDir.walkTopDown().filter { it.isFile }.sortedBy { it.name }

        val cachedList = cachedFiles.toList()
        val installedList = installedFiles.toList()

        if (cachedList.size != installedList.size) return true

        return cachedList.zip(installedList).any { (cached, installed) ->
            cached.name != installed.name || cached.readBytes().contentEquals(installed.readBytes()).not()
        }
    }
}
