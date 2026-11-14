package maestro.cli.db

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KeyValueStoreTest {
    @TempDir
    lateinit var tempDir: Path

    // These three cases come from the reproduction in issue #3414.
    @Test
    fun `read under lock from an empty file`() {
        val empty = File(tempDir.toFile(), "empty")
        Files.write(empty.toPath(), byteArrayOf())
        val store = KeyValueStore(empty)

        assertThat(checkLockDuring(empty) { store.keys() }).isEmpty()
    }

    @Test
    fun `read under lock from a nonempty file`() {
        val full = File(tempDir.toFile(), "nonempty")
        Files.writeString(full.toPath(), "ANDROID_x=1\n")
        val store = KeyValueStore(full)

        assertThat(checkLockDuring(full) { store.get("ANDROID_x") }).isEqualTo("1")
    }

    @Test
    fun `write under lock to an empty file`() {
        val empty = File(tempDir.toFile(), "empty")
        Files.write(empty.toPath(), byteArrayOf())
        val store = KeyValueStore(empty)

        checkLockDuring(empty) { store.set("k", "v") }

        assertThat(empty.readText()).isEqualTo("k=v")
        assertThat(KeyValueStore(empty).get("k")).isEqualTo("v")
    }

    @Test
    fun `update retains the lock and truncates old content`() {
        val file = tempDir.resolve("sessions").toFile()
        file.writeText("first=long-value\nsecond=retained")
        val store = KeyValueStore(file)

        checkLockDuring(file) { store.set("first", "x") }

        assertThat(file.readText()).isEqualTo("first=x\nsecond=retained")
        assertThat(store.keys()).containsExactly("first", "second")
    }

    @Test
    fun `delete retains the lock and truncates an empty store`() {
        val file = tempDir.resolve("sessions").toFile()
        file.writeText("k=v")
        val store = KeyValueStore(file)

        checkLockDuring(file) { store.delete("k") }

        assertThat(file.length()).isEqualTo(0L)
        assertThat(store.keys()).isEmpty()
    }

    @Test
    fun `values preserve UTF-8 and embedded equals signs`() {
        val file = tempDir.resolve("sessions").toFile()
        file.writeText("invalid line\r\nclé=été=東京\r\nother=kept")
        val store = KeyValueStore(file)

        assertThat(store.get("clé")).isEqualTo("été=東京")
        store.set("clé", "é")

        assertThat(file.readText()).isEqualTo("clé=é\nother=kept")
        assertThat(KeyValueStore(file).get("clé")).isEqualTo("é")
    }

    private fun <T> checkLockDuring(file: File, action: () -> T): T {
        var acquisitions = 0
        var releases = 0
        mockkConstructor(RandomAccessFile::class)
        try {
            every { anyConstructed<RandomAccessFile>().channel } answers {
                val channel = callOriginal() as FileChannel
                val wrapper = mockk<FileChannel>()
                every { wrapper.size() } answers { channel.size() }
                every { wrapper.position() } answers { channel.position() }
                every { wrapper.read(any<ByteBuffer>()) } answers { channel.read(firstArg<ByteBuffer>()) }
                every { wrapper.lock() } answers {
                    val lock = channel.lock()
                    acquisitions++
                    assertLockState(file, "BLOCKED")
                    object : FileLock(channel, lock.position(), lock.size(), lock.isShared) {
                        override fun isValid() = lock.isValid

                        override fun release() {
                            try {
                                releases++
                                assertLockState(file, "BLOCKED")
                            } finally {
                                lock.release()
                            }
                        }
                    }
                }
                wrapper
            }
            val result = action()
            assertThat(acquisitions).isEqualTo(1)
            assertThat(releases).isEqualTo(1)
            assertLockState(file, "ACQUIRED")
            return result
        } finally {
            unmockkConstructor(RandomAccessFile::class)
        }
    }

    private fun assertLockState(file: File, expected: String) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = Path.of(LockProbe::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val process = ProcessBuilder(java, "-cp", classes, LockProbe::class.java.name, file.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            assertThat(process.exitValue()).isEqualTo(0)
            assertThat(output).isEqualTo(expected)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}
