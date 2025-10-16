import com.google.common.truth.Truth.assertThat
import maestro.unicode.UnicodeDetector
import org.junit.jupiter.api.Test

class UnicodeDetectorTest {

    @Test
    fun `isAsciiOnly should return true for ASCII text`() {
        assertThat(UnicodeDetector.isAsciiOnly("Hello World")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("test123")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("Test with spaces and numbers 123")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("!@#$%^&*()")).isTrue()
    }

    @Test
    fun `isAsciiOnly should return false for Unicode text`() {
        assertThat(UnicodeDetector.isAsciiOnly("Tést inpüt")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("مرحبا بالعالم")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("你好世界")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("こんにちは世界")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("안녕하세요")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("Hello 👋 World")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("café")).isFalse()
    }

    @Test
    fun `isAsciiOnly should handle edge cases`() {
        assertThat(UnicodeDetector.isAsciiOnly("ASCII with newline\n")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("ASCII with tab\t")).isTrue()
        assertThat(UnicodeDetector.isAsciiOnly("Mixed ASCII 你好")).isFalse()
        assertThat(UnicodeDetector.isAsciiOnly("🌍")).isFalse()
    }

    @Test
    fun `getCharacterSets should identify ASCII`() {
        val sets = UnicodeDetector.getCharacterSets("Hello World")
        assertThat(sets).contains(UnicodeDetector.CharacterSet.ASCII)
        assertThat(sets).hasSize(1)
    }

    @Test
    fun `getCharacterSets should identify multiple character sets`() {
        val sets = UnicodeDetector.getCharacterSets("Hello 你好 مرحبا")
        assertThat(sets).contains(UnicodeDetector.CharacterSet.ASCII)
        assertThat(sets).contains(UnicodeDetector.CharacterSet.CJK)
        assertThat(sets).contains(UnicodeDetector.CharacterSet.ARABIC)
    }

    @Test
    fun `containsBidirectionalText should return true for RTL languages`() {
        assertThat(UnicodeDetector.containsBidirectionalText("مرحبا")).isTrue()
        assertThat(UnicodeDetector.containsBidirectionalText("שלום")).isTrue()
        assertThat(UnicodeDetector.containsBidirectionalText("Hello مرحبا")).isTrue()
    }

    @Test
    fun `containsBidirectionalText should return false for LTR languages`() {
        assertThat(UnicodeDetector.containsBidirectionalText("Hello World")).isFalse()
        assertThat(UnicodeDetector.containsBidirectionalText("你好世界")).isFalse()
        assertThat(UnicodeDetector.containsBidirectionalText("café")).isFalse()
    }

    @Test
    fun `containsEmoji should return true for emoji characters`() {
        // With proper Unicode code point handling, emoji should now be detected correctly
        assertThat(UnicodeDetector.containsEmoji("😀")).isTrue() // Face with grinning eyes
        assertThat(UnicodeDetector.containsEmoji("🌍")).isTrue() // Earth globe Europe-Africa
        assertThat(UnicodeDetector.containsEmoji("🚀")).isTrue() // Rocket
    }

    @Test
    fun `containsEmoji should return false for non-emoji text`() {
        assertThat(UnicodeDetector.containsEmoji("Hello World")).isFalse()
        assertThat(UnicodeDetector.containsEmoji("مرحبا بالعالم")).isFalse()
        assertThat(UnicodeDetector.containsEmoji("你好世界")).isFalse()
    }

    @Test
    fun `getRecommendedInputMethod should return STANDARD for ASCII`() {
        val method = UnicodeDetector.getRecommendedInputMethod("Hello World")
        assertThat(method).isEqualTo(UnicodeDetector.InputMethod.STANDARD)
    }

    @Test
    fun `getRecommendedInputMethod should return UNICODE_WITH_EMOJI_SUPPORT for emoji`() {
        // Emoji should now be properly detected and get emoji support method
        val method = UnicodeDetector.getRecommendedInputMethod("😀")
        assertThat(method).isEqualTo(UnicodeDetector.InputMethod.UNICODE_WITH_EMOJI_SUPPORT)
    }

    @Test
    fun `getRecommendedInputMethod should return UNICODE_WITH_IME for Arabic`() {
        val method = UnicodeDetector.getRecommendedInputMethod("مرحبا")
        assertThat(method).isEqualTo(UnicodeDetector.InputMethod.UNICODE_WITH_IME)
    }

    @Test
    fun `getTextComplexity should categorize text correctly`() {
        assertThat(UnicodeDetector.getTextComplexity("Hello"))
            .isEqualTo(UnicodeDetector.TextComplexity.SIMPLE)
        
        assertThat(UnicodeDetector.getTextComplexity("Hello 你好"))
            .isEqualTo(UnicodeDetector.TextComplexity.MODERATE)
        
        assertThat(UnicodeDetector.getTextComplexity("😀"))
            .isEqualTo(UnicodeDetector.TextComplexity.MODERATE) // Single character set, short text
    }
}