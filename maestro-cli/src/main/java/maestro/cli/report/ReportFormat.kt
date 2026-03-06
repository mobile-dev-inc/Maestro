package maestro.cli.report

import picocli.CommandLine

enum class ReportFormat(
    private val displayName: String? = null
) {

    JUNIT,
    HTML,
    HTML_DETAILED("HTML-DETAILED"),
    ALLURE,
    NOOP;

    override fun toString(): String {
        return displayName ?: name
    }

    class Converter : CommandLine.ITypeConverter<ReportFormat> {
        override fun convert(value: String): ReportFormat {
            // Try to match by display name first, then by enum name
            return values().find {
                it.toString().equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Invalid format: $value. Valid options are: ${values().joinToString()}")
        }
    }
}