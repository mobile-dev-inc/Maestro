package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Buffer
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class JUnitTestSuiteReporterTest {

    // Since timestamps we get from the server have milliseconds precision (specifically epoch millis)
    // we need to truncate off nanoseconds (and any higher) precision.
    val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)

    val nowPlus1 = now.plusSeconds(1)
    val nowPlus2 = now.plusSeconds(2)

    val nowAsIso = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val nowPlus1AsIso = nowPlus1.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val nowPlus2AsIso = nowPlus2.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

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
                            duration = 421573.milliseconds,
                            startTime = nowPlus1.toInstant().toEpochMilli()
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                            duration = 1494749.milliseconds,
                            startTime = nowPlus2.toInstant().toEpochMilli()
                        ),
                    ),
                    duration = 1915947.milliseconds,
                    startTime = now.toInstant().toEpochMilli()
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
                            duration = 421573.milliseconds,
                            startTime = nowPlus1.toInstant().toEpochMilli()
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.ERROR,
                            failure = TestExecutionSummary.Failure("Error message"),
                            duration = 131846.milliseconds,
                            startTime = nowPlus2.toInstant().toEpochMilli()
                        ),
                    ),
                    duration = 552743.milliseconds,
                    startTime = now.toInstant().toEpochMilli()
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
                            duration = 421573.milliseconds,
                            startTime = nowPlus1.toInstant().toEpochMilli()
                        ),
                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.WARNING,
                            startTime = nowPlus2.toInstant().toEpochMilli()
                        ),
                    ),
                    duration = 421573.milliseconds,
                    deviceName = "iPhone 14",
                    startTime = now.toInstant().toEpochMilli()
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
                  <testsuite name="Custom test suite name" device="iPhone 14" tests="2" failures="0" time="421.573" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

}
