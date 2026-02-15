package maestro.cli.report

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.google.common.truth.Truth.assertThat
import io.qameta.allure.model.Status
import io.qameta.allure.model.TestResult
import io.qameta.allure.util.ResultsUtils
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class AllureTestSuiteReporterTest : TestSuiteReporterTest() {

    private val mapper = JsonMapper.builder()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .build()

    @Test
    fun `report writes JSON result files into output directory`(@TempDir outputDir: File) {
        reporter(outputDir).report(testSuccessWithWarning)

        assertThat(readResults(outputDir)).hasSize(2)
    }

    @Test
    fun `result files contain correct flow names`(@TempDir outputDir: File) {
        reporter(outputDir).report(testSuccessWithWarning)

        val results = readResults(outputDir)
        val names = results.mapNotNull { it.name }.toSet()

        assertThat(names).containsExactly("Flow A", "Flow B")
        results.forEach { assertThat(it.historyId).isNotEmpty() }
    }

    @Test
    fun `result files contain framework host and suite labels`(@TempDir outputDir: File) {
        reporter(outputDir).report(testSuccessWithWarning)

        readResults(outputDir).forEach { result ->
            assertThat(labelValues(result, "framework")).contains("maestro")
            assertThat(labelValues(result, "host")).contains("iPhone 15")
            assertThat(labelValues(result, "suite")).contains("Test Suite")
        }
    }

    @Test
    fun `result files contain tags and properties as labels`(@TempDir outputDir: File) {
        reporter(outputDir).report(testWithTagsAndProperties)

        val loginFlow = readResultByName(outputDir, "Login Flow")
        val checkoutFlow = readResultByName(outputDir, "Checkout Flow")

        assertThat(labelValues(loginFlow, "tag")).containsAtLeast("smoke", "critical", "auth")
        assertThat(labelValues(loginFlow, "testCaseId")).contains("TC-001")
        assertThat(labelValues(loginFlow, "priority")).contains("P0")

        assertThat(labelValues(checkoutFlow, "tag")).containsAtLeast("regression", "e2e")
        assertThat(labelValues(checkoutFlow, "testCaseId")).contains("TC-002")
        assertThat(labelValues(checkoutFlow, "testrail-case-id")).contains("C456")
    }

    @Test
    fun `steps are mapped with expected status and timestamps`(@TempDir outputDir: File) {
        reporter(outputDir).report(testErrorWithSteps)

        val result = readResultByName(outputDir, "Flow B")

        assertThat(result.steps).hasSize(4)

        val firstStep = result.steps[0]
        assertThat(firstStep.name).isEqualTo("1. Launch app")
        assertThat(firstStep.status).isEqualTo(Status.PASSED)

        val warnedStep = result.steps[1]
        assertThat(warnedStep.name).isEqualTo("2. Tap on optional element")
        assertThat(warnedStep.status).isEqualTo(Status.BROKEN)
        assertThat(warnedStep.stop).isEqualTo(warnedStep.start)

        val failedStep = result.steps[2]
        assertThat(failedStep.name).isEqualTo("3. Tap on button")
        assertThat(failedStep.status).isEqualTo(Status.FAILED)

        val skippedStep = result.steps[3]
        assertThat(skippedStep.name).isEqualTo("4. Assert visible")
        assertThat(skippedStep.status).isEqualTo(Status.SKIPPED)
        assertThat(skippedStep.stop).isEqualTo(skippedStep.start)

        assertThat(result.steps.mapNotNull { it.start }).isInStrictOrder()
    }

    @Test
    fun `unknown step status maps to BROKEN`(@TempDir outputDir: File) {
        val summary = TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow With Unknown Step",
                            fileName = "flow_unknown_step",
                            status = FlowStatus.SUCCESS,
                            duration = 1000.milliseconds,
                            startTime = now.toInstant().toEpochMilli(),
                            steps = listOf(
                                TestExecutionSummary.StepResult(
                                    description = "1. Unknown",
                                    status = "SOMETHING_NEW",
                                    durationMs = 1,
                                    startTime = now.toInstant().toEpochMilli(),
                                )
                            ),
                        ),
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )

        reporter(outputDir).report(summary)

        val result = readResultByName(outputDir, "Flow With Unknown Step")
        assertThat(result.steps.single().status).isEqualTo(Status.BROKEN)
    }

    @Test
    fun `flow status mapping covers skipped failed broken and default`(@TempDir outputDir: File) {
        val summary = TestExecutionSummary(
            passed = false,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = false,
                    flows = listOf(
                        flow(name = "Canceled", status = FlowStatus.CANCELED),
                        flow(name = "Stopped", status = FlowStatus.STOPPED),
                        flow(name = "Error", status = FlowStatus.ERROR),
                        flow(name = "Warning", status = FlowStatus.WARNING),
                        flow(name = "Running", status = FlowStatus.RUNNING),
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )

        reporter(outputDir).report(summary)

        assertThat(readResultByName(outputDir, "Canceled").status).isEqualTo(Status.SKIPPED)
        assertThat(readResultByName(outputDir, "Stopped").status).isEqualTo(Status.SKIPPED)
        assertThat(readResultByName(outputDir, "Error").status).isEqualTo(Status.FAILED)
        assertThat(readResultByName(outputDir, "Warning").status).isEqualTo(Status.BROKEN)
        assertThat(readResultByName(outputDir, "Running").status).isEqualTo(Status.BROKEN)
    }

    @Test
    fun `attachments are copied and registered in result JSON`(@TempDir outputDir: File) {
        val summaryWithAttachments = createSummaryWithAttachment()

        reporter(outputDir).report(summaryWithAttachments)

        val result = readResultByName(outputDir, "Flow With Attachment")

        assertThat(result.attachments).hasSize(1)
        val attachment = result.attachments.single()
        assertThat(attachment.name).isEqualTo("screenshot")
        assertThat(attachment.type).isEqualTo("image/png")
        assertThat(attachment.source).endsWith(".png")
        assertThat(File(outputDir, attachment.source).exists()).isTrue()
    }

    @Test
    fun `missing attachments are skipped`(@TempDir outputDir: File) {
        val missingFile = File(outputDir, "missing.png")
        val summary = TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow With Missing Attachment",
                            fileName = "flow_missing_attachment",
                            status = FlowStatus.SUCCESS,
                            duration = 1000.milliseconds,
                            startTime = now.toInstant().toEpochMilli(),
                            attachments = listOf(
                                TestExecutionSummary.Attachment(
                                    file = missingFile,
                                    label = "screenshot",
                                    mimeType = "image/png",
                                )
                            ),
                        ),
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )

        reporter(outputDir).report(summary)

        val result = readResultByName(outputDir, "Flow With Missing Attachment")
        assertThat(result.attachments).isEmpty()
    }

    @Test
    fun `null suite name uses device as full name and no suite label`(@TempDir outputDir: File) {
        reporter(outputDir, null).report(testSuccessWithWarning)

        val result = readResultByName(outputDir, "Flow A")
        assertThat(result.fullName).isEqualTo("iPhone 15#Flow A")
        assertThat(labelValues(result, "suite")).isEmpty()
    }

    @Test
    fun `fallbacks are used for fullName and historyId when fields are missing`(@TempDir outputDir: File) {
        val summary = TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    deviceName = null,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow Without File",
                            fileName = null,
                            status = FlowStatus.SUCCESS,
                            duration = 1000.milliseconds,
                            startTime = now.toInstant().toEpochMilli(),
                        )
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )

        reporter(outputDir, null).report(summary)

        val result = readResultByName(outputDir, "Flow Without File")
        val expectedHistoryId = ResultsUtils.generateMethodSignatureHash("maestro.flow", "Flow Without File", emptyList())

        assertThat(result.fullName).isEqualTo("maestro#Flow Without File")
        assertThat(result.historyId).isEqualTo(expectedHistoryId)
    }

    @Test
    fun `env variables are included as parameters`(@TempDir outputDir: File) {
        val summaryWithEnv = TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow With Env",
                            fileName = "flow_env",
                            status = FlowStatus.SUCCESS,
                            duration = 1000.milliseconds,
                            startTime = now.toInstant().toEpochMilli(),
                            env = mapOf(
                                "MAESTRO_FILENAME" to "flow_env",
                                "MAESTRO_DEVICE_UDID" to "ABC-123",
                                "MAESTRO_SHARD_ID" to "1",
                                "API_URL" to "https://example.com",
                            ),
                        ),
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )

        reporter(outputDir).report(summaryWithEnv)

        val result = readResultByName(outputDir, "Flow With Env")
        val parameters = result.parameters.associate { it.name to it.value }

        assertThat(parameters).containsEntry("MAESTRO_FILENAME", "flow_env")
        assertThat(parameters).containsEntry("MAESTRO_DEVICE_UDID", "ABC-123")
        assertThat(parameters).containsEntry("MAESTRO_SHARD_ID", "1")
        assertThat(parameters).containsEntry("API_URL", "https://example.com")
    }

    @Test
    fun `failure message is included in status details`(@TempDir outputDir: File) {
        reporter(outputDir).report(testSuccessWithError)

        val failedResult = readResultByName(outputDir, "Flow B")

        assertThat(failedResult.status).isEqualTo(Status.FAILED)
        assertThat(failedResult.statusDetails?.message).isEqualTo("Error message")
    }

    private fun readResults(outputDir: File): List<TestResult> {
        val files = outputDir.listFiles { _, name -> name.endsWith("-result.json") }.orEmpty()
        return files.map { file -> mapper.readValue(file, TestResult::class.java) }
    }

    private fun readResultByName(outputDir: File, flowName: String): TestResult {
        return readResults(outputDir).single { it.name == flowName }
    }

    private fun labelValues(result: TestResult, labelName: String): List<String?> {
        return result.labels.filter { it.name == labelName }.map { it.value }
    }

    private fun createSummaryWithAttachment(): TestExecutionSummary {
        val screenshotFile = File.createTempFile("screenshot", ".png")
        screenshotFile.writeBytes(ByteArray(100))
        screenshotFile.deleteOnExit()

        return TestExecutionSummary(
            passed = true,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = true,
                    flows = listOf(
                        TestExecutionSummary.FlowResult(
                            name = "Flow With Attachment",
                            fileName = "flow_attach",
                            status = FlowStatus.SUCCESS,
                            duration = 1000.milliseconds,
                            startTime = now.toInstant().toEpochMilli(),
                            attachments = listOf(
                                TestExecutionSummary.Attachment(
                                    file = screenshotFile,
                                    label = "screenshot",
                                    mimeType = "image/png",
                                )
                            ),
                        ),
                    ),
                    duration = 1000.milliseconds,
                    startTime = now.toInstant().toEpochMilli(),
                )
            )
        )
    }

    private fun flow(name: String, status: FlowStatus): TestExecutionSummary.FlowResult {
        return TestExecutionSummary.FlowResult(
            name = name,
            fileName = name.lowercase(),
            status = status,
            duration = 1000.milliseconds,
            startTime = now.toInstant().toEpochMilli(),
        )
    }

    private fun reporter(outputDir: File, testSuiteName: String? = "Test Suite"): AllureTestSuiteReporter {
        return AllureTestSuiteReporter(
            outputDir = outputDir,
            testSuiteName = testSuiteName,
        )
    }
}
