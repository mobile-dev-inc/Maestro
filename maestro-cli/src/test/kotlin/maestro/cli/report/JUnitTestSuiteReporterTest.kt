package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class JUnitTestSuiteReporterTest : TestSuiteReporterTest() {

    @Test
    fun `XML - Test passed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(null, null)
        val reportOutput = File("tmpReportOutput")

        // When
        testee.report(
            summary = testSuccessWithWarning,
            out = reportOutput
        )
        val resultStr = reportOutput.readText(Charsets.UTF_8)

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Test failed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(null, null)
        val reportOutput = File("tmpReportOutput")

        // When
        testee.report(
            summary = testSuccessWithError,
            out = reportOutput
        )
        val resultStr = reportOutput.readText(Charsets.UTF_8)

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="1" time="552.743" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="131.846" timestamp="$nowPlus2AsIso" status="ERROR">
                      <failure>Error message</failure>
                    </testcase>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Custom test suite name is used when present`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml("Custom test suite name", "fileExtension")
        val reportOutput = File("tmpReportOutput")

        // When
        testee.report(
            summary = testSuccessWithWarning,
            out = reportOutput
        )
        val resultStr = reportOutput.readText(Charsets.UTF_8)

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Custom test suite name" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

}
