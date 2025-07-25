package maestro.unicode

/**
 * Detects and categorizes Unicode characters to determine the appropriate input method.
 * This class analyzes text to identify character sets and provides routing logic for
 * different input strategies.
 */
class UnicodeDetector {
    
    companion object {
        /**
         * Checks if a string contains only ASCII characters (0-127).
         */
        fun isAsciiOnly(text: String): Boolean {
            return text.all { it.code in 0..127 }
        }
        
        /**
         * Analyzes text and returns all character sets present.
         */
        fun getCharacterSets(text: String): Set<CharacterSet> {
            val sets = mutableSetOf<CharacterSet>()
            
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                
                when (codePoint) {
                    // ASCII range
                    in 0x0000..0x007F -> sets.add(CharacterSet.ASCII)
                    
                    // Latin Extended (accented characters)
                    in 0x0080..0x024F -> sets.add(CharacterSet.LATIN_EXTENDED)
                    
                    // Arabic
                    in 0x0600..0x06FF -> sets.add(CharacterSet.ARABIC)
                    
                    // Hebrew
                    in 0x0590..0x05FF -> sets.add(CharacterSet.HEBREW)
                    
                    // CJK Unified Ideographs (Chinese, Japanese, Korean)
                    in 0x4E00..0x9FFF -> sets.add(CharacterSet.CJK)
                    
                    // Korean Hangul
                    in 0xAC00..0xD7AF -> sets.add(CharacterSet.HANGUL)
                    
                    // Devanagari (Hindi, Sanskrit)
                    in 0x0900..0x097F -> sets.add(CharacterSet.DEVANAGARI)
                    
                    // Cyrillic
                    in 0x0400..0x04FF -> sets.add(CharacterSet.CYRILLIC)
                    
                    // Greek
                    in 0x0370..0x03FF -> sets.add(CharacterSet.GREEK)
                    
                    // Thai
                    in 0x0E00..0x0E7F -> sets.add(CharacterSet.THAI)
                    
                    // Emoji ranges (proper Unicode code points)
                    in 0x1F600..0x1F64F, // Emoticons
                    in 0x1F300..0x1F5FF, // Miscellaneous Symbols and Pictographs
                    in 0x1F680..0x1F6FF, // Transport and Map Symbols
                    in 0x1F700..0x1F77F, // Alchemical Symbols
                    in 0x1F780..0x1F7FF, // Geometric Shapes Extended
                    in 0x1F800..0x1F8FF, // Supplemental Arrows-C
                    in 0x1F900..0x1F9FF, // Supplemental Symbols and Pictographs
                    in 0x1FA00..0x1FA6F, // Chess Symbols
                    in 0x1FA70..0x1FAFF, // Symbols and Pictographs Extended-A
                    in 0x2600..0x26FF,   // Miscellaneous Symbols
                    in 0x2700..0x27BF    // Dingbats
                    -> sets.add(CharacterSet.EMOJI)
                    
                    // Other characters
                    else -> sets.add(CharacterSet.OTHER)
                }
                
                i += Character.charCount(codePoint)
            }
            
            return sets
        }
        
        /**
         * Determines if text contains bidirectional (RTL) characters.
         */
        fun containsBidirectionalText(text: String): Boolean {
            val sets = getCharacterSets(text)
            return sets.contains(CharacterSet.ARABIC) || 
                   sets.contains(CharacterSet.HEBREW)
        }
        
        /**
         * Checks if text requires special emoji handling.
         */
        fun containsEmoji(text: String): Boolean {
            return getCharacterSets(text).contains(CharacterSet.EMOJI)
        }
        
        /**
         * Determines the primary input method needed for the text.
         */
        fun getRecommendedInputMethod(text: String): InputMethod {
            val sets = getCharacterSets(text)
            
            return when {
                sets.size == 1 && sets.contains(CharacterSet.ASCII) -> InputMethod.STANDARD
                sets.contains(CharacterSet.EMOJI) -> InputMethod.UNICODE_WITH_EMOJI_SUPPORT
                sets.any { it.isComplexScript() } -> InputMethod.UNICODE_WITH_IME
                sets.any { it.isRightToLeft() } -> InputMethod.UNICODE_WITH_RTL_SUPPORT
                else -> InputMethod.UNICODE_STANDARD
            }
        }
        
        /**
         * Analyzes text complexity for performance optimization.
         */
        fun getTextComplexity(text: String): TextComplexity {
            val sets = getCharacterSets(text)
            val length = text.length
            
            return when {
                sets.size == 1 && sets.contains(CharacterSet.ASCII) && length < 100 -> 
                    TextComplexity.SIMPLE
                sets.size <= 2 && length < 500 -> 
                    TextComplexity.MODERATE
                sets.contains(CharacterSet.EMOJI) || sets.size > 3 -> 
                    TextComplexity.COMPLEX
                length > 1000 -> 
                    TextComplexity.VERY_COMPLEX
                else -> 
                    TextComplexity.MODERATE
            }
        }
    }
    
    /**
     * Represents different character sets and writing systems.
     */
    enum class CharacterSet {
        ASCII,
        LATIN_EXTENDED,
        ARABIC,
        HEBREW,
        CJK,
        HANGUL,
        DEVANAGARI,
        CYRILLIC,
        GREEK,
        THAI,
        EMOJI,
        OTHER;
        
        /**
         * Returns true if this character set uses complex script features.
         */
        fun isComplexScript(): Boolean {
            return when (this) {
                DEVANAGARI, THAI, ARABIC, HEBREW -> true
                else -> false
            }
        }
        
        /**
         * Returns true if this character set uses right-to-left writing.
         */
        fun isRightToLeft(): Boolean {
            return when (this) {
                ARABIC, HEBREW -> true
                else -> false
            }
        }
    }
    
    /**
     * Represents different input methods based on text requirements.
     */
    enum class InputMethod {
        STANDARD,                    // ASCII characters only
        UNICODE_STANDARD,           // Basic Unicode support
        UNICODE_WITH_RTL_SUPPORT,   // RTL languages like Arabic, Hebrew
        UNICODE_WITH_IME,           // Complex scripts requiring IME
        UNICODE_WITH_EMOJI_SUPPORT  // Emoji and special symbols
    }
    
    /**
     * Represents text complexity levels for performance optimization.
     */
    enum class TextComplexity {
        SIMPLE,      // ASCII only, short text
        MODERATE,    // Mixed scripts, medium length
        COMPLEX,     // Multiple scripts, emoji, or special characters
        VERY_COMPLEX // Very long text or highly mixed content
    }
}