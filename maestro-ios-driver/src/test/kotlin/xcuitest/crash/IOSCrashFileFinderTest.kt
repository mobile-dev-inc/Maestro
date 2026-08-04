package xcuitest.crash

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.concurrent.thread

class IOSCrashFileFinderTest {

    private val testSimulatorId = "TEST-SIM-UUID-1234"
    private val otherSimulatorId = "OTHER-SIM-UUID-5678"
    private val testBundleId = "com.example.testapp"

    @Nested
    inner class FindCrashFile {

        @Test
        fun `finds crash file matching simulator UUID and bundleId`(@TempDir tempDir: File) {
            val ipsFile = File(tempDir, "TestApp-2024-01-15.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isEqualTo(ipsFile)
        }

        @Test
        fun `ignores crash from different simulator`(@TempDir tempDir: File) {
            File(tempDir, "TestApp-2024-01-15.ips").apply {
                writeText(buildIpsFile(otherSimulatorId, "TestApp", testBundleId))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `ignores crash from different bundleId`(@TempDir tempDir: File) {
            File(tempDir, "TestApp-2024-01-15.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", "com.example.differentapp"))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `returns most recent when multiple crashes exist`(@TempDir tempDir: File) {
            val older = File(tempDir, "TestApp-old.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
                setLastModified(1000)
            }
            val newer = File(tempDir, "TestApp-new.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
                setLastModified(System.currentTimeMillis())
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isEqualTo(newer)
        }

        @Test
        fun `handles missing directories gracefully`() {
            val finder = IOSCrashFileFinder(listOf(File("/does/not/exist")))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `ignores non-ips files`(@TempDir tempDir: File) {
            File(tempDir, "TestApp-2024-01-15.txt").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `finds crash regardless of filename - matches by bundleId content`(@TempDir tempDir: File) {
            // Filename uses app_name "DasherRed" but bundleId is "com.doordash.dasher.RedApp"
            val ipsFile = File(tempDir, "DasherRed-2024-01-15.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "DasherRed", "com.doordash.dasher.RedApp"))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, "com.doordash.dasher.RedApp")

            assertThat(result).isEqualTo(ipsFile)
        }
    }

    @Nested
    inner class MultipleDirectories {

        @Test
        fun `searches across multiple directories`(@TempDir tempDir1: File, @TempDir tempDir2: File) {
            val ipsFile = File(tempDir2, "TestApp-2024-01-15.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir1, tempDir2))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isEqualTo(ipsFile)
        }

        @Test
        fun `returns most recent across directories`(@TempDir tempDir1: File, @TempDir tempDir2: File) {
            File(tempDir1, "TestApp-old.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
                setLastModified(1000)
            }
            val newerFile = File(tempDir2, "TestApp-new.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
                setLastModified(System.currentTimeMillis())
            }

            val finder = IOSCrashFileFinder(listOf(tempDir1, tempDir2))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isEqualTo(newerFile)
        }
    }

    @Nested
    inner class MalformedFiles {

        @Test
        fun `handles malformed IPS files gracefully`(@TempDir tempDir: File) {
            File(tempDir, "TestApp-corrupted.ips").apply {
                writeText("not valid json at all")
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `handles empty files gracefully`(@TempDir tempDir: File) {
            File(tempDir, "TestApp-empty.ips").apply {
                writeText("")
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isNull()
        }

        @Test
        fun `skips malformed and returns valid files`(@TempDir tempDir: File) {
            // File with bundleId in content but malformed JSON
            File(tempDir, "TestApp-corrupted.ips").apply {
                writeText(""""bundleID":"$testBundleId" but not valid json""")
            }
            val validFile = File(tempDir, "TestApp-valid.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val finder = IOSCrashFileFinder(listOf(tempDir))
            val result = finder.findCrashFile(testSimulatorId, testBundleId)

            assertThat(result).isEqualTo(validFile)
        }
    }

    @Nested
    inner class WaitForCrashFile {

        @Test
        fun `returns a crash report that appears after the crash`(@TempDir tempDir: File) {
            val finder = IOSCrashFileFinder(listOf(tempDir))
            val sinceEpochMs = System.currentTimeMillis()
            val writer = thread {
                Thread.sleep(200)
                File(tempDir, "TestApp.ips").writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val result = finder.waitForCrashFile(testSimulatorId, testBundleId, sinceEpochMs, timeoutMs = 5000, pollIntervalMs = 20)

            writer.join()
            assertThat(result).isNotNull()
        }

        @Test
        fun `returns null when no crash report appears within the timeout`(@TempDir tempDir: File) {
            val finder = IOSCrashFileFinder(listOf(tempDir))

            val result = finder.waitForCrashFile(testSimulatorId, testBundleId, System.currentTimeMillis(), timeoutMs = 300, pollIntervalMs = 20)

            assertThat(result).isNull()
        }

        @Test
        fun `ignores a stale report and waits for the fresh one`(@TempDir tempDir: File) {
            val sinceEpochMs = System.currentTimeMillis()
            File(tempDir, "Stale.ips").apply {
                writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
                setLastModified(sinceEpochMs - 60_000)
            }
            val finder = IOSCrashFileFinder(listOf(tempDir))
            val writer = thread {
                Thread.sleep(200)
                File(tempDir, "Fresh.ips").writeText(buildIpsFile(testSimulatorId, "TestApp", testBundleId))
            }

            val result = finder.waitForCrashFile(testSimulatorId, testBundleId, sinceEpochMs, timeoutMs = 5000, pollIntervalMs = 20)

            writer.join()
            assertThat(result?.name).isEqualTo("Fresh.ips")
        }
    }

    // Test helper to build IPS files with the expected format
    private fun buildIpsFile(
        simulatorId: String,
        appName: String,
        bundleId: String
    ): String {
        return """{"app_name":"$appName","timestamp":"2024-01-15 10:00:00.00 -0800","bundleID":"$bundleId"}
{
  "procName": "$appName",
  "coalitionName": "com.apple.CoreSimulator.SimDevice.$simulatorId",
  "exception": {"type": "EXC_CRASH", "signal": "SIGKILL"}
}"""
    }
}
