package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Buffer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class JUnitTestSuiteReporterTest {

    @Test
    fun `XML - Test passed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()

        val summary = TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    deviceName = "iPhone 15",
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow A",
                            fileName = "flow_a",
                            status = FlowStatus.SUCCESS,
                            duration = 421.seconds
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                            duration = 1494.seconds
                        ),
                    ),
                    duration = 1915.seconds,
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
                  <testsuite name="Test Suite" device="iPhone 15" tests="2" failures="0" time="1915" passingPercentage="100.0">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="1494" status="WARNING"/>
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
                            duration = 421.seconds
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.ERROR,
                            failure = TestExecutionSummary.Failure("Error message"),
                            duration = 131.seconds
                        ),
                    ),
                    duration = 552.seconds,
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
                  <testsuite name="Test Suite" tests="2" failures="1" time="552" passingPercentage="50.0">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="131" status="ERROR">
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
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow A",
                            fileName = "flow_a",
                            status = FlowStatus.SUCCESS,
                            duration = 421.seconds
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                        ),
                    ),
                    duration = 421.seconds,
                    deviceName = "iPhone 14",
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
                  <testsuite name="Custom test suite name" device="iPhone 14" tests="2" failures="0" time="421" passingPercentage="100.0">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

}
