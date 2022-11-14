package maestro.cli.runner

import maestro.Maestro
import maestro.cli.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.util.PrintUtils
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File

object TestSuiteInteractor {

    fun runTestSuite(
        maestro: Maestro,
        device: Device?,
        input: File,
        env: Map<String, String>,
    ): SuiteResult {
        return if (input.isFile) {
            runTestSuite(
                maestro, device, listOf(input), env
            )
        } else {
            val flowFiles = input
                .listFiles()
                .filter {
                    it.isFile
                        && it.extension in setOf("yaml", "yml")
                        && it.nameWithoutExtension != "config"
                }
                .toList()

            runTestSuite(
                maestro, device, flowFiles, env
            )
        }
    }

    fun runTestSuite(
        maestro: Maestro,
        device: Device?,
        flows: List<File>,
        env: Map<String, String>,
    ): SuiteResult {
        val flowResults = mutableListOf<SuiteResult.FlowResult>()

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
            // - Errors
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

        return SuiteResult(
            passed = passed,
            flows = flowResults,
            device = device,
        )
    }

    private fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
    ): SuiteResult.FlowResult {
        var flowName: String = flowFile.nameWithoutExtension
        var flowStatus: FlowStatus

        try {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
                .map { it.injectEnv(env) }

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

            // TODO preserve error for report
        }

        TestSuiteStatusView.showFlowCompletion(
            TestSuiteViewModel.FlowResult(
                name = flowName,
                status = flowStatus,
            )
        )

        return SuiteResult.FlowResult(
            name = flowName,
            status = flowStatus,
        )
    }

    data class SuiteResult(
        val passed: Boolean,
        val flows: List<FlowResult>,
        val device: Device? = null,
    ) {

        data class FlowResult(
            val name: String,
            val status: FlowStatus,  // TODO do not depend on view model
        )

    }

}