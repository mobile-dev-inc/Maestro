package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Buffer
import org.junit.jupiter.api.Test

class JUnitTestSuiteReporterTest {

    @Test
    fun `XML - Test passed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()

        val summary = TestExecutionSummary(
            passed = true,
            deviceName = "iPhone 14",
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow A",
                            fileName = "flow_a",
                            status = FlowStatus.SUCCESS,
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                        ),
                    )
                )
            )
        )
        val sink = Buffer()

        // When
        testee.report(
            summary = summary,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 14" tests="2" failures="0">
                    <testcase id="flow_a" name="Flow A" classname="flow_a"/>
                    <testcase id="flow_b" name="Flow B" classname="flow_b"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Test failed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()

        val summary = TestExecutionSummary(
            passed = false,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = false,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow A",
                            fileName = "flow_a",
                            status = FlowStatus.SUCCESS,
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.ERROR,
                            failure = TestExecutionSummary.Failure("Error message")
                        ),
                    )
                )
            )
        )
        val sink = Buffer()

        // When
        testee.report(
            summary = summary,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="1">
                    <testcase id="flow_a" name="Flow A" classname="flow_a"/>
                    <testcase id="flow_b" name="Flow B" classname="flow_b">
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
        val testee = JUnitTestSuiteReporter.xml("Custom test suite name")

        val summary = TestExecutionSummary(
            passed = true,
            deviceName = "iPhone 14",
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow A",
                            fileName = "flow_a",
                            status = FlowStatus.SUCCESS,
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                        ),
                    )
                )
            )
        )
        val sink = Buffer()

        // When
        testee.report(
            summary = summary,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Custom test suite name" device="iPhone 14" tests="2" failures="0">
                    <testcase id="flow_a" name="Flow A" classname="flow_a"/>
                    <testcase id="flow_b" name="Flow B" classname="flow_b"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

}