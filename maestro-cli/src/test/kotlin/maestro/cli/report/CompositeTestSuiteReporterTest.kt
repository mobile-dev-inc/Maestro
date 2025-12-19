package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CompositeTestSuiteReporterTest {

    @Test
    fun `Composite test report created`() {
        // Given
        val reportFormat = ReportFormat.COMPOSITE

        // When
        val reporter = reportFormat.createReporter("testSuiteName")

        // Then
        assertThat(reporter is CompositeTestSuiteReporter).isTrue()
    }

    @Test
    fun `Composite test report contains JUNIT and HTML reports`() {
        // Given
        val reportFormat = ReportFormat.COMPOSITE

        // When
        val reporter = reportFormat.createReporter("testSuiteName")
        val reporters = (reporter as CompositeTestSuiteReporter).reporters

        // Then
        assertThat(reporters.any { it is JUnitTestSuiteReporter }).isTrue()
        assertThat(reporters.any { it is HtmlTestSuiteReporter }).isTrue()
    }
}
