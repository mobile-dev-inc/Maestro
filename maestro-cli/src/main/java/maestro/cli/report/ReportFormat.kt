package maestro.cli.report

enum class ReportFormat(
    val fileExtension: String?
) {

    JUNIT(".xml"),
    NOOP(null),

}