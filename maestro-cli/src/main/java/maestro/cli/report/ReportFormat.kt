package maestro.cli.report

enum class ReportFormat(
    val fileExtension: String?
) {

    JUNIT(".xml"),
    HTML(".html"),
    NOOP(null),

}