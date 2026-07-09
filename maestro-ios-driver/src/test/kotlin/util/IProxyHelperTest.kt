package util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

class IProxyHelperTest {

    @Test
    fun `forward command binds only to loopback`() {
        assertThat(
            IProxyHelper.buildForwardCommand(
                binary = Paths.get("/opt/libusbmuxd/bin/iproxy"),
                hostPort = 22087,
                devicePort = 22087,
                udid = "UDID-1",
            )
        ).containsExactly(
            "/opt/libusbmuxd/bin/iproxy",
            "--udid", "UDID-1",
            "--local",
            "--source", "127.0.0.1",
            "22087:22087",
        ).inOrder()
    }

    @Test
    fun `resolveBinary finds Maestro managed binary`(@TempDir home: Path) {
        val binary = executable(home.resolve(".maestro/deps/iproxy"))

        assertThat(IProxyHelper.resolveBinary(env = emptyMap(), home = home)).isEqualTo(binary)
    }

    @Test
    fun `resolveBinary falls back to PATH`(@TempDir home: Path, @TempDir binDir: Path) {
        val binary = executable(binDir.resolve("iproxy"))

        assertThat(
            IProxyHelper.resolveBinary(env = mapOf("PATH" to binDir.toString()), home = home)
        ).isEqualTo(binary)
    }

    @Test
    fun `invalid explicit binary fails with actionable error`(@TempDir home: Path) {
        val missing = home.resolve("missing-iproxy")

        val error = assertThrows<IProxyNotFoundException> {
            IProxyHelper.resolveBinary(
                env = mapOf("MAESTRO_IPROXY_PATH" to missing.toString()),
                home = home,
            )
        }

        assertThat(error).hasMessageThat().contains("MAESTRO_IPROXY_PATH")
    }

    @Test
    fun `capability check accepts all forward command options`(@TempDir directory: Path) {
        val binary = script(
            directory.resolve("iproxy"),
            """
            printf '%s\n' 'Usage: iproxy [OPTIONS]' '  --udid UDID' '  --local' '  --source ADDR'
            """.trimIndent(),
        )

        IProxyHelper.checkRequiredCapabilities(binary, timeoutMillis = 1_000)
    }

    @Test
    fun `capability check rejects missing forward command option`(@TempDir directory: Path) {
        val binary = script(
            directory.resolve("iproxy"),
            """
            printf '%s\n' 'Usage: iproxy [OPTIONS]' '  --udid UDID' '  --local'
            """.trimIndent(),
        )

        val error = assertThrows<IProxyNotFoundException> {
            IProxyHelper.checkRequiredCapabilities(binary, timeoutMillis = 1_000)
        }

        assertThat(error).hasMessageThat().contains("--source")
        assertThat(error).hasMessageThat().contains("libusbmuxd 2.0.2 or newer")
    }

    @Test
    fun `capability check rejects failed help command`(@TempDir directory: Path) {
        val binary = script(
            directory.resolve("iproxy"),
            """
            echo 'help failed' >&2
            exit 23
            """.trimIndent(),
        )

        val error = assertThrows<IProxyNotFoundException> {
            IProxyHelper.checkRequiredCapabilities(binary, timeoutMillis = 1_000)
        }

        assertThat(error).hasMessageThat().contains("exited with code 23")
        assertThat(error).hasMessageThat().contains("help failed")
    }

    @Test
    fun `capability check times out and terminates help command`(@TempDir directory: Path) {
        val binary = script(directory.resolve("iproxy"), "exec sleep 30")

        val error = assertThrows<IProxyNotFoundException> {
            IProxyHelper.checkRequiredCapabilities(binary, timeoutMillis = 100)
        }

        assertThat(error).hasMessageThat().contains("did not finish within 100ms")
        assertThat(error).hasMessageThat().contains("libusbmuxd 2.0.2 or newer")
    }

    @Test
    fun `does not take over a forward owned by an active Maestro process`(@TempDir home: Path) {
        val binary = Paths.get("/bin/sleep")
        val forward = ProcessBuilder(binary.toString(), "30").start()
        val hostPort = 22087
        val devicePort = 22087
        val udid = "UDID-1"
        val owner = ProcessHandle.current()
        val directory = home.resolve(".maestro/iproxy").also(Files::createDirectories)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$udid:$hostPort:$devicePort".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val registration = directory.resolve("$digest.properties")

        try {
            Files.newBufferedWriter(registration).use { writer ->
                Properties().apply {
                    setProperty("pid", forward.pid().toString())
                    setProperty(
                        "startedAt",
                        forward.toHandle().info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString(),
                    )
                    setProperty("ownerPid", owner.pid().toString())
                    setProperty(
                        "ownerStartedAt",
                        owner.info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString(),
                    )
                    setProperty("binary", binary.toRealPath().toString())
                    setProperty("udid", udid)
                    setProperty("hostPort", hostPort.toString())
                    setProperty("devicePort", devicePort.toString())
                }.store(writer, null)
            }

            IProxyHelper(binary = binary, home = home).use { helper ->
                val error = assertThrows<IProxyForwardException> {
                    helper.forward(hostPort, devicePort, udid)
                }
                assertThat(error).hasMessageThat().contains("another active Maestro session")
                assertThat(forward.isAlive).isTrue()
            }
        } finally {
            forward.destroyForcibly()
            forward.waitFor()
        }
    }

    private fun executable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        return path
    }

    private fun script(path: Path, body: String): Path {
        Files.writeString(path, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }
}
