package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReporterFactoryTest : TestSuiteReporterTest() {

    @Test
    fun `JUNIT reporter writes to provided file`(@TempDir tempDir: File) {
        val outputFile = File(tempDir, "junit.xml")

        ReporterFactory
            .buildReporter(ReportFormat.JUNIT, outputFile, testSuiteName = null)
            .report(testSuccessWithWarning)

        assertThat(outputFile.exists()).isTrue()
        assertThat(outputFile.readText()).contains("<testsuites>")
    }

    @Test
    fun `HTML reporter writes to provided file`(@TempDir tempDir: File) {
        val outputFile = File(tempDir, "report.html")

        ReporterFactory
            .buildReporter(ReportFormat.HTML, outputFile, testSuiteName = null)
            .report(testSuccessWithWarning)

        assertThat(outputFile.exists()).isTrue()
        assertThat(outputFile.readText()).contains("<html>")
    }

    @Test
    fun `ALLURE reporter writes into provided directory`(@TempDir tempDir: File) {
        val outputDir = File(tempDir, "allure-results")

        ReporterFactory
            .buildReporter(ReportFormat.ALLURE, outputDir, testSuiteName = "Suite")
            .report(testSuccessWithWarning)

        val jsonFiles = outputDir.listFiles { _, name -> name.endsWith("-result.json") }
        assertThat(jsonFiles).isNotNull()
        assertThat(jsonFiles!!.size).isEqualTo(2)
    }

    @Test
    fun `ALLURE reporter rejects file output path`(@TempDir tempDir: File) {
        val outputFile = File(tempDir, "allure-results")
        outputFile.writeText("not-a-directory")

        val error = assertThrows<CliError> {
            ReporterFactory.buildReporter(ReportFormat.ALLURE, outputFile, testSuiteName = null)
        }

        assertThat(error).hasMessageThat().contains("must be a directory")
    }
}
