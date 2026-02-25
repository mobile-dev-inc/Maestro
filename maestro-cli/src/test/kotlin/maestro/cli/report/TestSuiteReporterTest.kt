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

    val testSuccessWithSteps = TestExecutionSummary(
        passed = true,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = true,
                flows = listOf(
                    TestExecutionSummary.FlowResult(
                        name = "Flow A",
                        fileName = "flow_a",
                        status = FlowStatus.SUCCESS,
                        duration = 5000.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli(),
                        steps = listOf(
                            TestExecutionSummary.StepResult(
                                description = "1. Launch app",
                                status = "COMPLETED",
                                durationMs = 1200,
                                startTime = nowPlus1.toInstant().toEpochMilli(),
                            ),
                            TestExecutionSummary.StepResult(
                                description = "2. Tap on button",
                                status = "COMPLETED",
                                durationMs = 500,
                                startTime = nowPlus1.toInstant().toEpochMilli() + 1200,
                            ),
                            TestExecutionSummary.StepResult(
                                description = "3. Assert visible",
                                status = "COMPLETED",
                                durationMs = 100,
                                startTime = nowPlus1.toInstant().toEpochMilli() + 1700,
                            ),
                        )
                    ),
                ),
                duration = 5000.milliseconds,
                startTime = now.toInstant().toEpochMilli()
            )
        )
    )

    val testErrorWithSteps = TestExecutionSummary(
        passed = false,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = false,
                flows = listOf(
                    run {
                        val flowStartMs = nowPlus1.toInstant().toEpochMilli()
                        val launchStepStartMs = flowStartMs
                        val warnedStepStartMs = launchStepStartMs + 1500L
                        val warnedStepElapsedMs = 2000L // warned steps don't track duration, but time still passes
                        val failedStepStartMs = warnedStepStartMs + warnedStepElapsedMs
                        val skippedStepStartMs = failedStepStartMs + 2000L

                        TestExecutionSummary.FlowResult(
                            name = "Flow B",
                            fileName = "flow_b",
                            status = FlowStatus.ERROR,
                            failure = TestExecutionSummary.Failure("Element not found"),
                            duration = 3000.milliseconds,
                            startTime = flowStartMs,
                            steps = listOf(
                                TestExecutionSummary.StepResult(
                                    description = "1. Launch app",
                                    status = "COMPLETED",
                                    durationMs = 1500L,
                                    startTime = launchStepStartMs,
                                ),
                                TestExecutionSummary.StepResult(
                                    description = "2. Tap on optional element",
                                    status = "WARNED",
                                    durationMs = null,
                                    startTime = warnedStepStartMs,
                                ),
                                TestExecutionSummary.StepResult(
                                    description = "3. Tap on button",
                                    status = "FAILED",
                                    durationMs = 2000L,
                                    startTime = failedStepStartMs,
                                ),
                                TestExecutionSummary.StepResult(
                                    description = "4. Assert visible",
                                    status = "SKIPPED",
                                    durationMs = 0,
                                    startTime = skippedStepStartMs,
                                ),
                            )
                        )
                    },
                ),
                duration = 3000.milliseconds,
                startTime = now.toInstant().toEpochMilli()
            )
        )
    )

    val testWithTagsAndProperties = TestExecutionSummary(
        passed = true,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = true,
                flows = listOf(
                    TestExecutionSummary.FlowResult(
                        name = "Login Flow",
                        fileName = "login_flow",
                        status = FlowStatus.SUCCESS,
                        duration = 2500.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli(),
                        tags = listOf("smoke", "critical", "auth"),
                        properties = mapOf(
                            "testCaseId" to "TC-001",
                            "xray-test-key" to "PROJ-123",
                            "priority" to "P0"
                        )
                    ),
                    TestExecutionSummary.FlowResult(
                        name = "Checkout Flow",
                        fileName = "checkout_flow",
                        status = FlowStatus.SUCCESS,
                        duration = 3500.milliseconds,
                        startTime = nowPlus2.toInstant().toEpochMilli(),
                        tags = listOf("regression", "e2e"),
                        properties = mapOf(
                            "testCaseId" to "TC-002",
                            "testrail-case-id" to "C456"
                        )
                    ),
                ),
                duration = 6000.milliseconds,
                startTime = now.toInstant().toEpochMilli()
            )
        )
    )
}
