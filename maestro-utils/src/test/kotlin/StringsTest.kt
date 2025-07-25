import com.google.common.truth.Truth.assertThat
import maestro.utils.chunkStringByWordCount
import maestro.utils.drawTextBox
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

    @Test
    fun `drawTextBox simple`() {
        assertThat(drawTextBox("hello", 10)).isEqualTo("""
            ╭───────╮
            │ hello │
            ╰───────╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox empty`() {
        assertThat(drawTextBox("", 10)).isEqualTo("""
            ╭──╮
            │  │
            ╰──╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox long word`() {
        assertThat(drawTextBox("reallyreallyreallyreallyreallyreallylongword", 10)).isEqualTo("""
            ╭────────╮
            │ really │
            │ really │
            │ really │
            │ really │
            │ really │
            │ really │
            │ longwo │
            │ rd     │
            ╰────────╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox long line`() {
        assertThat(drawTextBox("really really really really really really long line", 10)).isEqualTo("""
            ╭────────╮
            │ really │
            │ really │
            │ really │
            │ really │
            │ really │
            │ really │
            │ long   │
            │ line   │
            ╰────────╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox single line`() {
        assertThat(drawTextBox("a single line", 50)).isEqualTo("""
            ╭───────────────╮
            │ a single line │
            ╰───────────────╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox multi line`() {
        assertThat(drawTextBox("""
            first line
            second line
        """.trimIndent(), 80)).isEqualTo("""
            ╭─────────────╮
            │ first line  │
            │ second line │
            ╰─────────────╯
        """.trimIndent())
    }

    @Test
    fun `drawTextBox overflow long word`() {
        assertThat(drawTextBox("""
            there is a reallyreallyreallyreallylongword in this line
        """.trimIndent(), 20)).isEqualTo("""
            ╭──────────────────╮
            │ there is a       │
            │ reallyreallyreal │
            │ lyreallylongword │
            │ in this line     │
            ╰──────────────────╯
        """.trimIndent())
    }
}