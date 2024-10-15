import maestro.utils.isRegularFile
import maestro.utils.isSingleFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CollectionsTest {

    @Test
    fun `isSingleFile should return true for a single regular file`() {
        val file = Files.createTempFile("testFile", ".txt").toFile()
        val files = listOf(file)
        assertTrue(files.isSingleFile)
        file.delete()
    }

    @Test
    fun `isSingleFile should return false for multiple files`() {
        val file1 = Files.createTempFile("testFile1", ".txt").toFile()
        val file2 = Files.createTempFile("testFile2", ".txt").toFile()
        val files = listOf(file1, file2)
        assertFalse(files.isSingleFile)
        file1.delete()
        file2.delete()
    }

    @Test
    fun `isSingleFile should return false for a single directory`() {
        val dir = Files.createTempDirectory("testDir").toFile()
        val files = listOf(dir)
        assertFalse(files.isSingleFile)
        dir.delete()
    }

    @Test
    fun `isRegularFile should return true for a single regular file`() {
        val file = Files.createTempFile("testFile", ".txt")
        val paths = listOf(file)
        assertTrue(paths.isRegularFile)
        Files.delete(file)
    }

    @Test
    fun `isRegularFile should return false for multiple files`() {
        val file1 = Files.createTempFile("testFile1", ".txt")
        val file2 = Files.createTempFile("testFile2", ".txt")
        val paths = listOf(file1, file2)
        assertFalse(paths.isRegularFile)
        Files.delete(file1)
        Files.delete(file2)
    }

    @Test
    fun `isRegularFile should return false for a single directory`() {
        val dir = Files.createTempDirectory("testDir")
        val paths = listOf(dir)
        assertFalse(paths.isRegularFile)
        Files.delete(dir)
    }
}