package maestro.web.input

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object HtmlDateInputFormatter {

    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        normalizeIsoDate(trimmed)?.let { return it }
        normalizeSeparatedDate(trimmed)?.let { return it }

        val digits = trimmed.filter { it.isDigit() }
        if (digits.length != 8 || digits.length != trimmed.length) {
            return null
        }

        return listOfNotNull(
            normalizeYearMonthDay(digits),
            normalizeMonthDayYear(digits),
            normalizeDayMonthYear(digits),
        ).firstOrNull()
    }

    private fun normalizeIsoDate(text: String): String? {
        if (!ISO_DATE_REGEX.matches(text)) {
            return null
        }

        return normalizeDate(
            year = text.substring(0, 4).toInt(),
            month = text.substring(5, 7).toInt(),
            day = text.substring(8, 10).toInt(),
        )
    }

    private fun normalizeSeparatedDate(text: String): String? {
        val match = SEPARATED_DATE_REGEX.matchEntire(text) ?: return null
        val first = match.groupValues[1]
        val second = match.groupValues[2]
        val third = match.groupValues[3]

        return if (first.length == 4) {
            normalizeDate(
                year = first.toInt(),
                month = second.toInt(),
                day = third.toInt(),
            )
        } else {
            if (third.length != 4) {
                return null
            }

            normalizeDate(
                year = third.toInt(),
                month = first.toInt(),
                day = second.toInt(),
            ) ?: normalizeDate(
                year = third.toInt(),
                month = second.toInt(),
                day = first.toInt(),
            )
        }
    }

    private fun normalizeYearMonthDay(digits: String): String? {
        return normalizeDate(
            year = digits.substring(0, 4).toInt(),
            month = digits.substring(4, 6).toInt(),
            day = digits.substring(6, 8).toInt(),
        )
    }

    private fun normalizeMonthDayYear(digits: String): String? {
        return normalizeDate(
            year = digits.substring(4, 8).toInt(),
            month = digits.substring(0, 2).toInt(),
            day = digits.substring(2, 4).toInt(),
        )
    }

    private fun normalizeDayMonthYear(digits: String): String? {
        return normalizeDate(
            year = digits.substring(4, 8).toInt(),
            month = digits.substring(2, 4).toInt(),
            day = digits.substring(0, 2).toInt(),
        )
    }

    private fun normalizeDate(year: Int, month: Int, day: Int): String? {
        if (year < 1) {
            return null
        }

        return try {
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeException) {
            null
        }
    }

    private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    private val SEPARATED_DATE_REGEX = Regex("""(\d{1,4})[/-](\d{1,2})[/-](\d{1,4})""")
}
