package maestro.cli.report

import io.qameta.allure.AllureLifecycle
import io.qameta.allure.FileSystemResultsWriter
import io.qameta.allure.model.Attachment
import io.qameta.allure.model.Label
import io.qameta.allure.model.Parameter
import io.qameta.allure.model.Status
import io.qameta.allure.model.StatusDetails
import io.qameta.allure.model.StepResult
import io.qameta.allure.model.TestResult
import io.qameta.allure.util.ResultsUtils
import maestro.cli.CliError
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import java.io.File
import java.nio.file.Files
import java.util.UUID

class AllureTestSuiteReporter(
    private val outputDir: File,
    private val testSuiteName: String?,
) : TestSuiteReporter {

    companion object {
        fun from(output: File?, testSuiteName: String?): AllureTestSuiteReporter {
            return AllureTestSuiteReporter(
                outputDir = resolveOutputDir(output),
                testSuiteName = testSuiteName,
            )
        }

        private fun resolveOutputDir(output: File?): File {
            val outputDir = output ?: error("ALLURE output directory is required")
            if (outputDir.exists() && outputDir.isFile) {
                throw CliError("ALLURE report output must be a directory: ${outputDir.path}")
            }
            return outputDir
        }
    }

    override fun report(summary: TestExecutionSummary) {
        reportToDirectory(summary)
    }

    private fun reportToDirectory(summary: TestExecutionSummary) {
        outputDir.mkdirs()

        val lifecycle = AllureLifecycle(FileSystemResultsWriter(outputDir.toPath()))

        for (suite in summary.suites) {
            for (flow in suite.flows) {
                val uuid = UUID.randomUUID().toString()
                val flowStart = flow.startTime ?: 0L

                val steps = mapSteps(flow.steps, flowStart)
                val stepsEnd = steps.lastOrNull()?.stop
                val flowDurationEnd = flow.startTime?.let { start ->
                    flow.duration?.let { duration -> start + duration.inWholeMilliseconds }
                }
                val flowStop = listOfNotNull(flowDurationEnd, stepsEnd).maxOrNull() ?: flowStart

                val testResult = TestResult()
                    .setUuid(uuid)
                    .setName(flow.name)
                    .setFullName(buildFullName(suite, flow))
                    .setHistoryId(buildHistoryId(flow))
                    .setStatus(mapFlowStatus(flow.status))
                    .setLabels(buildLabels(suite, flow))
                    .setParameters(buildParameters(flow.env))
                    .setSteps(steps)
                    .setStart(flowStart)
                    .setStop(flowStop)

                if (flow.failure != null) {
                    testResult.setStatusDetails(
                        StatusDetails().setMessage(flow.failure.message)
                    )
                }

                val allureAttachments = copyAttachments(flow, outputDir)
                testResult.setAttachments(allureAttachments)

                lifecycle.scheduleTestCase(testResult)
                lifecycle.writeTestCase(uuid)
            }
        }
    }

    private fun mapFlowStatus(status: FlowStatus): Status {
        return when (status) {
            FlowStatus.SUCCESS -> Status.PASSED
            FlowStatus.ERROR -> Status.FAILED
            FlowStatus.WARNING -> Status.BROKEN
            FlowStatus.CANCELED, FlowStatus.STOPPED -> Status.SKIPPED
            else -> Status.BROKEN
        }
    }

    private fun mapStepStatus(status: String): Status {
        return when (status.uppercase()) {
            "COMPLETED" -> Status.PASSED
            "FAILED" -> Status.FAILED
            "WARNED" -> Status.BROKEN
            "SKIPPED" -> Status.SKIPPED
            else -> Status.BROKEN
        }
    }

    private fun mapSteps(steps: List<TestExecutionSummary.StepResult>, flowStartTime: Long): List<StepResult> {
        return steps.map { step ->
            val start = step.startTime ?: flowStartTime
            val durationMs = step.durationMs ?: 0L
            val stop = start + durationMs
            StepResult()
                .setName(step.description)
                .setStatus(mapStepStatus(step.status))
                .setStart(start)
                .setStop(stop)
        }
    }

    private fun buildFullName(
        suite: TestExecutionSummary.SuiteResult,
        flow: TestExecutionSummary.FlowResult,
    ): String {
        val suitePart = testSuiteName ?: suite.deviceName ?: "maestro"
        return "$suitePart#${flow.name}"
    }

    private fun buildHistoryId(flow: TestExecutionSummary.FlowResult): String {
        val clazz = flow.fileName ?: "maestro.flow"
        val method = flow.name
        return ResultsUtils.generateMethodSignatureHash(clazz, method, emptyList())
    }

    private fun buildLabels(
        suite: TestExecutionSummary.SuiteResult,
        flow: TestExecutionSummary.FlowResult,
    ): List<Label> {
        val labels = mutableListOf<Label>()

        labels.add(Label().setName("framework").setValue("maestro"))

        if (testSuiteName != null) {
            labels.add(Label().setName("suite").setValue(testSuiteName))
        }

        if (suite.deviceName != null) {
            labels.add(Label().setName("host").setValue(suite.deviceName))
        }

        flow.tags?.forEach { tag ->
            labels.add(Label().setName("tag").setValue(tag))
        }

        flow.properties?.forEach { (key, value) ->
            labels.add(Label().setName(key).setValue(value))
        }

        return labels
    }

    private fun buildParameters(env: Map<String, String>): List<Parameter> {
        return env.map { (key, value) ->
            Parameter().setName(key).setValue(value)
        }
    }

    private fun copyAttachments(flow: TestExecutionSummary.FlowResult, outputDir: File): List<Attachment> {
        return flow.attachments.mapNotNull { attachment ->
            if (!attachment.file.exists()) return@mapNotNull null

            val extension = attachment.file.extension
                .takeIf { it.isNotBlank() }
                ?.let { ".$it" }
                .orEmpty()
            val destName = "${UUID.randomUUID()}-${attachment.label}${extension}"
            val destFile = File(outputDir, destName)
            Files.copy(attachment.file.toPath(), destFile.toPath())

            Attachment()
                .setName(attachment.label)
                .setSource(destName)
                .setType(attachment.mimeType)
        }
    }
}
