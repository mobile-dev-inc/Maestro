package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HtmlAITestSuiteReporterTest {

    @Test
    fun `report does not crash when the flow name contains a path separator`(@TempDir tempDir: File) {
        // Given - a flow whose name contains a '/', which is a path separator on unix-like systems.
        // See issue #2017: writing "ai-report-foo / bar.html" used to be interpreted as a path into
        // a non-existent "foo " directory and crashed with FileNotFoundException.
        val reporter = HtmlAITestSuiteReporter()
        val output = FlowAIOutput(
            flowName = "foo / bar",
            flowFile = File(tempDir, "flow.yaml").apply { writeText("appId: com.example") },
            screenOutputs = mutableListOf(),
        )

        // When
        reporter.report(listOf(output), tempDir)

        // Then - the report is written to a single, sanitized file inside the output directory.
        val reportFile = File(tempDir, "ai-report-foo _ bar.html")
        assertThat(reportFile.exists()).isTrue()
    }
}
