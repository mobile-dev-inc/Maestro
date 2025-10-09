package maestro.cli.report

import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

abstract class TestSuiteReporterTest {

    // Since timestamps we get from the server have milliseconds precision (specifically epoch millis)
    // we need to truncate off nanoseconds (and any higher) precision.
    val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)

    val nowPlus1 = now.plusSeconds(1)
    val nowPlus2 = now.plusSeconds(2)

    val nowAsIso = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val nowPlus1AsIso = nowPlus1.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val nowPlus2AsIso = nowPlus2.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    val testSuccessWithWarning = TestExecutionSummary(
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

    val testSuccessWithError = TestExecutionSummary(
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
}
