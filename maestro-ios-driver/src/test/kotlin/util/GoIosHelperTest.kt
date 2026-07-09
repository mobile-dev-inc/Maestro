package util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

class GoIosHelperTest {

    private val binary = Paths.get("/opt/go-ios/ios")
    private val pairRecordPath = Paths.get("/tmp/go-ios-pair-records")
    private val tunnelInfoPort = 28101

    @Test
    fun `tunnel command uses userspace mode`() {
        assertEquals(
            listOf(
                "/opt/go-ios/ios", "tunnel", "start", "--userspace",
                "--pair-record-path=/tmp/go-ios-pair-records",
                "--tunnel-info-port=28101",
                "--udid=UDID-1",
            ),
            GoIosHelper.buildTunnelCommand(binary, "UDID-1", pairRecordPath, tunnelInfoPort),
        )
    }

    @Test
    fun `setlocation command passes coordinates`() {
        assertEquals(
            listOf(
                "/opt/go-ios/ios", "setlocation", "--lat=52.52", "--lon=13.405",
                "--tunnel-info-port=28101", "--udid=UDID-1",
            ),
            GoIosHelper.buildSetLocationCommand(binary, 52.52, 13.405, "UDID-1", tunnelInfoPort),
        )
    }

    @Test
    fun `syslog and crash export commands`() {
        assertEquals(
            listOf(
                "/opt/go-ios/ios", "syslog", "--nojson",
                "--tunnel-info-port=28101", "--udid=UDID-1",
            ),
            GoIosHelper.buildSyslogCommand(binary, "UDID-1", tunnelInfoPort),
        )
        assertEquals(
            listOf("/opt/go-ios/ios", "crash", "cp", "*", "/tmp/crashes", "--udid=UDID-1"),
            GoIosHelper.buildCrashExportCommand(binary, File("/tmp/crashes"), "UDID-1"),
        )
    }

    @Test
    fun `kill command targets bundle id`() {
        assertEquals(
            listOf(
                "/opt/go-ios/ios", "kill", "com.example.app",
                "--tunnel-info-port=28101", "--udid=UDID-1",
            ),
            GoIosHelper.buildKillCommand(binary, "com.example.app", "UDID-1", tunnelInfoPort),
        )
    }

    @Test
    fun `setLocation accepts successful short-lived legacy command`(@TempDir tempDir: Path) {
        val fakeBinary = tempDir.resolve("ios")
        Files.writeString(fakeBinary, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(fakeBinary, PosixFilePermissions.fromString("rwxr-xr-x"))

        GoIosHelper(
            binary = fakeBinary,
            pairRecordPath = tempDir.resolve("pair-records"),
            tunnelInfoPort = tunnelInfoPort,
        ).use { helper ->
            helper.setLocation(52.52, 13.405, "UDID-1")
        }
    }

    @Test
    fun `default pair record path is Maestro owned`(@TempDir home: Path) {
        assertEquals(
            home.resolve(".maestro/deps/go-ios/pair-records"),
            GoIosHelper.defaultPairRecordPath(home),
        )
    }

    @Test
    fun `resolveBinary throws actionable error when nothing is installed`(@TempDir home: Path) {
        val e = assertThrows(GoIosNotFoundException::class.java) {
            GoIosHelper.resolveBinary(env = emptyMap(), home = home)
        }
        assert(e.message!!.contains("MAESTRO_GO_IOS_PATH"))
    }

    @Test
    fun `resolveBinary refuses non-executable explicit path`(@TempDir home: Path) {
        val notExecutable = home.resolve("ios")
        Files.createFile(notExecutable)
        assertThrows(GoIosNotFoundException::class.java) {
            GoIosHelper.resolveBinary(env = mapOf("MAESTRO_GO_IOS_PATH" to notExecutable.toString()), home = home)
        }
    }

    @Test
    fun `resolveBinary finds binary under maestro deps dir`(@TempDir home: Path) {
        val depsDir = home.resolve(".maestro/deps/go-ios")
        Files.createDirectories(depsDir)
        val bin = depsDir.resolve("ios")
        Files.createFile(bin, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))

        assertEquals(bin, GoIosHelper.resolveBinary(env = emptyMap(), home = home))
    }

    @Test
    fun `resolveBinary falls back to PATH`(@TempDir home: Path, @TempDir pathDir: Path) {
        val bin = pathDir.resolve("go-ios")
        Files.createFile(bin, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))

        assertEquals(
            bin,
            GoIosHelper.resolveBinary(env = mapOf("PATH" to pathDir.toString()), home = home),
        )
    }
}
