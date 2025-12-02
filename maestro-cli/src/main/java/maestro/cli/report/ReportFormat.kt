package maestro.cli.report

import picocli.CommandLine

enum class ReportFormat(
    val fileExtension: String?,
    private val displayName: String? = null
) {

    JUNIT(".xml"),
    HTML(".html"),
    HTML_DETAILED(".html", "HTML-DETAILED"),
    NOOP(null);

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