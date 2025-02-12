import maestro.utils.chunkStringByWordCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StringsTest {

    @Test
    fun `chunkStringByWordCount should return empty list for empty string`() {
        val result = "".chunkStringByWordCount(2)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `chunkStringByWordCount should return single chunk for string with fewer words than chunk size`() {
        val result = "hello world".chunkStringByWordCount(3)
        assertEquals(listOf("hello world"), result)
    }

    @Test
    fun `chunkStringByWordCount should return multiple chunks for string with more words than chunk size`() {
        val result = "hello world this is a test".chunkStringByWordCount(2)
        assertEquals(listOf("hello world", "this is", "a test"), result)
    }

    @Test
    fun `chunkStringByWordCount should handle exact chunk size`() {
        val result = "hello world this is a test".chunkStringByWordCount(5)
        assertEquals(listOf("hello world this is a", "test"), result)
    }

    @Test
    fun `chunkStringByWordCount should handle trailing spaces`() {
        val result = "  hello   world  ".chunkStringByWordCount(1)
        assertEquals(listOf("hello", "world"), result)
    }

    @Test
    fun `chunkStringByWordCount should handle multiple spaces between words`() {
        val result = "hello   world this  is   a test".chunkStringByWordCount(2)
        assertEquals(listOf("hello world", "this is", "a test"), result)
    }
}