package maestro.utils

object StringUtils {

    fun String.toRegexSafe(option: RegexOption) = toRegexSafe(setOf(option))

    fun String.toRegexSafe(options: Set<RegexOption> = emptySet()): Regex {
        return try {
            toRegex(options)
        } catch (e: Exception) {
            Regex.escape(this).toRegex(options)
        }
    }

}