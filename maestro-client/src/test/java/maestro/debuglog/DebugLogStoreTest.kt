package maestro.debuglog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

internal class DebugLogStoreTest {

    @Test
    internal fun `pruneLogs keeps the newest zip archives and deletes older ones`(@TempDir tempDir: Path) {
        // Given more finalized run archives than the keep count
        val baseDir = tempDir.toFile()
        val zips = (1..8).map { File(baseDir, "2026-06-0${it}_100000_111.zip").apply { writeText("z") } }

        // When pruning, keeping the newest 6
        pruneLogs(baseDir, keepZipCount = 6, orphanDirCutoffMillis = 0)

        // Then only the 2 oldest archives are removed
        assertThat(zips[0].exists()).isFalse()
        assertThat(zips[1].exists()).isFalse()
        zips.drop(2).forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    internal fun `pruneLogs deletes orphaned working dirs older than cutoff but keeps recent ones`(@TempDir tempDir: Path) {
        // Given a fresh (live) working dir and an old orphaned one
        val baseDir = tempDir.toFile()
        val freshDir = File(baseDir, "2026-06-02_120000_222").apply { mkdirs(); File(this, "maestro.log").writeText("x") }
        val orphanDir = File(baseDir, "2026-05-01_120000_333").apply { mkdirs(); File(this, "maestro.log").writeText("x") }
        val cutoff = 1_000_000L
        orphanDir.setLastModified(cutoff - 1)
        freshDir.setLastModified(cutoff + 1)

        // When pruning with that cutoff
        pruneLogs(baseDir, keepZipCount = 6, orphanDirCutoffMillis = cutoff)

        // Then the orphan is reaped and the live dir is left untouched
        assertThat(orphanDir.exists()).isFalse()
        assertThat(freshDir.exists()).isTrue()
    }

    @Test
    internal fun `pruneLogs does nothing when base dir does not exist`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("nope").toFile()

        // Should not throw
        pruneLogs(missing, keepZipCount = 6, orphanDirCutoffMillis = 0)

        assertThat(missing.exists()).isFalse()
    }
}
