package maestro.cli.report

import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

abstract class TestSuiteReporterTest {

    // A fixed instant + fixed zone make timestamp rendering deterministic across machines, so the
    // expected strings below can be asserted as exact literals (the reporters accept an injected
    // zoneId for exactly this reason). 1_700_000_000_000 ms = 2023-11-14T22:13:20Z, which is
    // 2023-11-15T03:43:20 in Asia/Kolkata (+05:30, no DST -> stable year-round).
    val testZoneId: ZoneId = ZoneId.of("Asia/Kolkata")
    val now: OffsetDateTime = Instant.ofEpochMilli(1_700_000_000_000L).atOffset(ZoneOffset.UTC)
    val nowPlus1: OffsetDateTime = now.plusSeconds(1)
    val nowPlus2: OffsetDateTime = now.plusSeconds(2)

    // JUnit timestamp: ISO-8601 local date-time, rendered in testZoneId.
    val nowAsIso = "2023-11-15T03:43:20"
    val nowPlus1AsIso = "2023-11-15T03:43:21"
    val nowPlus2AsIso = "2023-11-15T03:43:22"

    // HTML: the exact `<time>` element the reporter renders in testZoneId (machine-readable ISO
    // offset in `datetime`, friendly timezone-qualified text as content).
    val nowAsTime = """<time datetime="2023-11-15T03:43:20+05:30">Nov 15, 2023, 03:43:20 IST</time>"""
    val nowPlus1AsTime = """<time datetime="2023-11-15T03:43:21+05:30">Nov 15, 2023, 03:43:21 IST</time>"""
    val nowPlus2AsTime = """<time datetime="2023-11-15T03:43:22+05:30">Nov 15, 2023, 03:43:22 IST</time>"""

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
                        filePath = ".maestro/flow_a.yaml",
                        status = FlowStatus.SUCCESS,
                        duration = 421573.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli()
                    ),
                    TestExecutionSummary.FlowResult(
                        name = "Flow B",
                        fileName = "flow_b",
                        filePath = ".maestro/sub/flow_b.yaml",
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
                        filePath = ".maestro/flow_a.yaml",
                        status = FlowStatus.SUCCESS,
                        duration = 421573.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli()
                    ),
                    TestExecutionSummary.FlowResult(
                        name = "Flow B",
                        fileName = "flow_b",
                        filePath = ".maestro/sub/flow_b.yaml",
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

    val testSuccessWithoutFilePath = TestExecutionSummary(
        passed = true,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = true,
                deviceName = "iPhone 15",
                flows = listOf(
                    TestExecutionSummary.FlowResult(
                        name = "Cloud Flow",
                        fileName = null,
                        filePath = null,
                        status = FlowStatus.SUCCESS,
                        duration = 1000.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli()
                    ),
                ),
                duration = 1000.milliseconds,
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
                                duration = "1.2s"
                            ),
                            TestExecutionSummary.StepResult(
                                description = "2. Tap on button",
                                status = "COMPLETED",
                                duration = "500ms"
                            ),
                            TestExecutionSummary.StepResult(
                                description = "3. Assert visible",
                                status = "COMPLETED",
                                duration = "100ms"
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
                    TestExecutionSummary.FlowResult(
                        name = "Flow B",
                        fileName = "flow_b",
                        status = FlowStatus.ERROR,
                        failure = TestExecutionSummary.Failure("Element not found"),
                        duration = 3000.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli(),
                        steps = listOf(
                            TestExecutionSummary.StepResult(
                                description = "1. Launch app",
                                status = "COMPLETED",
                                duration = "1.5s"
                            ),
                            TestExecutionSummary.StepResult(
                                description = "2. Tap on optional element",
                                status = "WARNED",
                                duration = "<1ms"
                            ),
                            TestExecutionSummary.StepResult(
                                description = "3. Tap on button",
                                status = "FAILED",
                                duration = "2.0s"
                            ),
                            TestExecutionSummary.StepResult(
                                description = "4. Assert visible",
                                status = "SKIPPED",
                                duration = "0ms"
                            ),
                        )
                    ),
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
                        filePath = ".maestro/auth/login.yaml",
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
                        filePath = ".maestro/checkout.yaml",
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

    // startTime deliberately left null on both suite and flow (e.g. data unavailable).
    val testWithoutStartTime = TestExecutionSummary(
        passed = true,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = true,
                deviceName = "iPhone 15",
                flows = listOf(
                    TestExecutionSummary.FlowResult(
                        name = "Flow A",
                        fileName = "flow_a",
                        filePath = ".maestro/flow_a.yaml",
                        status = FlowStatus.SUCCESS,
                        duration = 1000.milliseconds,
                    ),
                ),
                duration = 1000.milliseconds,
            )
        )
    )

    val testWithCloudMetadata = TestExecutionSummary(
        passed = true,
        suites = listOf(
            TestExecutionSummary.SuiteResult(
                passed = true,
                deviceName = "iPhone 15",
                cloudUploadId = "abc123",
                cloudUploadUrl = "https://app.maestro.dev/project/proj_1/maestro-test/app/app_1/upload/abc123",
                flows = listOf(
                    TestExecutionSummary.FlowResult(
                        name = "Login Flow",
                        fileName = "login_flow",
                        filePath = ".maestro/auth/login.yaml",
                        status = FlowStatus.SUCCESS,
                        duration = 2500.milliseconds,
                        startTime = nowPlus1.toInstant().toEpochMilli(),
                        cloudRunId = "run-987",
                        cloudRunUrl = "https://app.maestro.dev/project/proj_1/maestro-test/flow/run-987"
                    ),
                ),
                duration = 2500.milliseconds,
                startTime = now.toInstant().toEpochMilli()
            )
        )
    )

    val testWithCustomIdAndClassname = TestExecutionSummary(
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
                        properties = mapOf(
                            "junitId" to "TC-LOGIN-001",
                            "junitClassname" to "com.example.tests.LoginTest"
                        )
                    ),
                    TestExecutionSummary.FlowResult(
                        name = "Checkout Flow",
                        fileName = "checkout_flow",
                        status = FlowStatus.SUCCESS,
                        duration = 3500.milliseconds,
                        startTime = nowPlus2.toInstant().toEpochMilli()
                    ),
                ),
                duration = 6000.milliseconds,
                startTime = now.toInstant().toEpochMilli()
            )
        )
    )
}
