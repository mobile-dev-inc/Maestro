package maestro.cli

import maestro.cli.report.ReportFormat
import picocli.CommandLine
import java.io.File

class ReportOutputMixin {

    @CommandLine.Option(
        names = ["--format"],
        description = ["Test report format (default=\${DEFAULT-VALUE}): \${COMPLETION-CANDIDATES}"],
        converter = [ReportFormat.Converter::class],
    )
    var format: ReportFormat = ReportFormat.NOOP

    @CommandLine.Option(
        names = ["--test-suite-name"],
        description = ["Test suite name"],
    )
    var testSuiteName: String? = null

    @CommandLine.Option(
        names = ["--output"],
        description = ["File to write report into (default=report.xml)"],
    )
    var output: File? = null
}
