package maestro.cli.view

import maestro.cli.api.UploadStatus
import maestro.cli.model.FlowStatus
import maestro.cli.util.PrintUtils
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel.FlowResult
import org.fusesource.jansi.Ansi
import java.util.UUID

object TestSuiteStatusView {

    fun showFlowCompletion(result: FlowResult) {
        printStatus(result.status)
        print(" ${result.name}")

        if (result.status == FlowStatus.WARNING) {
            print(
                Ansi.ansi()
                    .fgYellow()
                    .render(" (Warning)")
                    .fgDefault()
            )
        }

        println()
    }

    fun showSuiteResult(
        suite: TestSuiteViewModel,
    ) {
        if (suite.status == FlowStatus.ERROR) {
            val failedFlows = suite.flows
                .filter { it.status == FlowStatus.ERROR }

            PrintUtils.err(
                "${failedFlows.size}/${suite.flows.size} ${flowWord(failedFlows.size)} Failed",
                bold = true,
            )
        } else {
            val passedFlows = suite.flows
                .filter { it.status == FlowStatus.SUCCESS || it.status == FlowStatus.WARNING }

            val canceledFlows = suite.flows
                .filter { it.status == FlowStatus.CANCELED }

            if (passedFlows.isNotEmpty()) {
                PrintUtils.success(
                    "${passedFlows.size}/${suite.flows.size} ${flowWord(passedFlows.size)} Passed",
                    bold = true,
                )

                if (canceledFlows.isNotEmpty()) {
                    println("(${canceledFlows.size} ${flowWord(canceledFlows.size)} Canceled)")
                }
            } else {
                println()
                println("Upload Canceled")
            }
        }
        println()

        if (suite.uploadDetais != null) {
            println("==== View details in the console ====")
            PrintUtils.message(
                uploadUrl(
                    suite.uploadDetais.uploadId.toString(),
                    suite.uploadDetais.teamId,
                    suite.uploadDetais.appId,
                )
            )
            println()
        }
    }

    private fun printStatus(status: FlowStatus) {
        val color = when (status) {
            FlowStatus.SUCCESS,
            FlowStatus.WARNING -> Ansi.Color.GREEN
            FlowStatus.ERROR -> Ansi.Color.RED
            else -> Ansi.Color.DEFAULT
        }
        val title = when (status) {
            FlowStatus.SUCCESS,
            FlowStatus.WARNING -> "Passed"
            FlowStatus.ERROR -> "Failed"
            FlowStatus.PENDING -> "Pending"
            FlowStatus.RUNNING -> "Running"
            FlowStatus.CANCELED -> "Canceled"
        }

        print(
            Ansi.ansi()
                .fgBright(color)
                .render("[$title]")
                .fgDefault()
        )
    }

    fun uploadUrl(
        uploadId: String,
        teamId: String,
        appId: String
    ) = "https://console.mobile.dev/uploads/$uploadId?teamId=$teamId&appId=$appId"

    private fun flowWord(count: Int) = if (count == 1) "Flow" else "Flows"

    data class TestSuiteViewModel(
        val status: FlowStatus,
        val flows: List<FlowResult>,
        val uploadDetais: UploadDetails? = null,
    ) {

        data class FlowResult(
            val name: String,
            val status: FlowStatus,
        )

        data class UploadDetails(
            val uploadId: UUID,
            val teamId: String,
            val appId: String,
        )

        companion object {

            fun UploadStatus.toViewModel(
                uploadDetais: UploadDetails
            ) = TestSuiteViewModel(
                uploadDetais = uploadDetais,
                status = FlowStatus.from(status),
                flows = flows.map {
                    it.toViewModel()
                }
            )

            fun UploadStatus.FlowResult.toViewModel() = FlowResult(
                name = name,
                status = FlowStatus.from(status),
            )

        }

    }

}

// Helped launcher to play around with presentation
fun main() {
    val status = TestSuiteStatusView.TestSuiteViewModel(
        uploadDetais = TestSuiteStatusView.TestSuiteViewModel.UploadDetails(
            uploadId = UUID.randomUUID(),
            teamId = "teamid",
            appId = "appid",
        ),
        status = FlowStatus.CANCELED,
        flows = listOf(
            FlowResult(
                name = "A",
                status = FlowStatus.SUCCESS,
            ),
            FlowResult(
                name = "B",
                status = FlowStatus.SUCCESS,
            ),
            FlowResult(
                name = "C",
                status = FlowStatus.CANCELED,
            )
        )
    )

    status.flows
        .forEach {
            TestSuiteStatusView.showFlowCompletion(it)
        }

    TestSuiteStatusView.showSuiteResult(status)
}
