package maestro.cli.runner

import maestro.Maestro
import maestro.cli.CliError
import maestro.cli.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.TestSuiteReporter
import maestro.cli.util.PrintUtils
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.cli.view.ErrorViewUtils
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import okio.Sink
import java.io.File

class TestSuiteInteractor(
    private val maestro: Maestro,
    private val device: Device? = null,
    private val reporter: TestSuiteReporter,
    private val includeTags: List<String> = emptyList(),
    private val excludeTags: List<String> = emptyList(),
) {

    fun runTestSuite(
        input: File,
        reportOut: Sink?,
        env: Map<String, String>,
    ): TestExecutionSummary {
        return if (input.isFile) {
            runTestSuite(
                listOf(input),
                reportOut,
                env,
            )
        } else {
            val flowFiles = WorkspaceExecutionPlanner
                .plan(
                    input = input.toPath().toAbsolutePath(),
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                )
                .flowsToRun

            if (flowFiles.isEmpty()) {
                throw CliError("No flow returned from the tag filter used")
            }

            runTestSuite(
                flowFiles.map { it.toFile() },
                reportOut,
                env,
            )
        }
    }

    private fun runTestSuite(
        flows: List<File>,
        reportOut: Sink?,
        env: Map<String, String>,
    ): TestExecutionSummary {
        val flowResults = mutableListOf<TestExecutionSummary.FlowResult>()

        PrintUtils.message("Waiting for flows to complete...")
        println()

        var passed = true
        flows.forEach { flowFile ->
            val result = runFlow(flowFile, env, maestro)

            if (result.status == FlowStatus.ERROR) {
                passed = false
            }

            // TODO accumulate extra information
            // - Command statuses
            // - View hierarchies
            flowResults.add(result)
        }

        TestSuiteStatusView.showSuiteResult(
            TestSuiteViewModel(
                status = if (passed) FlowStatus.SUCCESS else FlowStatus.ERROR,
                flows = flowResults
                    .map {
                        TestSuiteViewModel.FlowResult(
                            name = it.name,
                            status = it.status,
                        )
                    },
            )
        )

        val summary = TestExecutionSummary(
            passed = passed,
            deviceName = device?.description,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = passed,
                    flows = flowResults
                )
            )
        )

        if (reportOut != null) {
            reporter.report(
                summary,
                reportOut
            )
        }

        return summary
    }

    private fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
    ): TestExecutionSummary.FlowResult {
        var flowName: String = flowFile.nameWithoutExtension
        var flowStatus: FlowStatus
        var errorMessage: String? = null

        try {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
                .withEnv(env)

            val config = YamlCommandReader.getConfig(commands)

            val orchestra = Orchestra(
                maestro = maestro
            )

            config?.name?.let {
                flowName = it
            }

            val flowSuccess = orchestra.runFlow(commands)
            flowStatus = if (flowSuccess) FlowStatus.SUCCESS else FlowStatus.ERROR
        } catch (e: Exception) {
            flowStatus = FlowStatus.ERROR

            errorMessage = ErrorViewUtils.exceptionToMessage(e)
        }

        TestSuiteStatusView.showFlowCompletion(
            TestSuiteViewModel.FlowResult(
                name = flowName,
                status = flowStatus,
            )
        )

        return TestExecutionSummary.FlowResult(
            name = flowName,
            fileName = flowFile.nameWithoutExtension,
            status = flowStatus,
            failure = if (flowStatus == FlowStatus.ERROR) {
                TestExecutionSummary.Failure(
                    message = errorMessage ?: "Unknown error",
                )
            } else null,
        )
    }

}
