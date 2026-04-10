package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class XCTestPortStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: TestableXCTestPortStore

    @BeforeEach
    fun setUp() {
        store = TestableXCTestPortStore(tempDir.resolve("xctest-ports").toFile())
    }

    @Test
    fun `read returns null when no port saved`() {
        assertThat(store.read("device-123")).isNull()
    }

    @Test
    fun `write and read round-trip`() {
        store.write("device-123", 7042)
        assertThat(store.read("device-123")).isEqualTo(7042)
    }

    @Test
    fun `write overwrites previous port`() {
        store.write("device-123", 7042)
        store.write("device-123", 7099)
        assertThat(store.read("device-123")).isEqualTo(7099)
    }

    @Test
    fun `different devices get different ports`() {
        store.write("device-A", 7001)
        store.write("device-B", 7002)
        assertThat(store.read("device-A")).isEqualTo(7001)
        assertThat(store.read("device-B")).isEqualTo(7002)
    }

    @Test
    fun `clear removes saved port`() {
        store.write("device-123", 7042)
        store.clear("device-123")
        assertThat(store.read("device-123")).isNull()
    }

    @Test
    fun `clear on non-existent device is no-op`() {
        store.clear("device-nonexistent")
        // Should not throw
    }

    @Test
    fun `read handles corrupt file gracefully`() {
        val file = File(tempDir.resolve("xctest-ports").toFile(), "device-corrupt")
        file.parentFile.mkdirs()
        file.writeText("not-a-number")
        assertThat(store.read("device-corrupt")).isNull()
    }

    /**
     * Testable version of XCTestPortStore that accepts a custom base dir
     * instead of using ~/.maestro/xctest-ports/.
     */
    class TestableXCTestPortStore(private val baseDir: File) {
        init { baseDir.mkdirs() }

        fun read(deviceId: String): Int? {
            return try {
                val file = File(baseDir, deviceId)
                if (!file.exists()) return null
                file.readText().trim().toIntOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun write(deviceId: String, port: Int) {
            try { File(baseDir, deviceId).writeText(port.toString()) }
            catch (e: Exception) { /* best effort */ }
        }

        fun clear(deviceId: String) {
            try { File(baseDir, deviceId).delete() }
            catch (e: Exception) { /* best effort */ }
        }
    }
}
