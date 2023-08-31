package maestro.cli.view

import maestro.cli.api.UploadStatus
import maestro.cli.model.FlowStatus
import maestro.cli.util.PrintUtils
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel.FlowResult
import org.fusesource.jansi.Ansi
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object TestSuiteStatusView {

    fun showFlowCompletion(result: FlowResult) {
        printStatus(result.status, result.cancellationReason)

        val durationString = result.duration?.let { " ($it)" }.orEmpty()
        print(" ${result.name}$durationString")
        if (result.status == FlowStatus.ERROR && result.error != null) {
            print(
                Ansi.ansi()
                    .fgRed()
                    .render(" (${result.error})")
                    .fgDefault()
            )
        }
        else if (result.status == FlowStatus.WARNING) {
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
        val hasError = suite.flows.find { it.status == FlowStatus.ERROR } != null
        val canceledFlows = suite.flows
            .filter { it.status == FlowStatus.CANCELED }

        if (suite.status == FlowStatus.ERROR || hasError) {
            val failedFlows = suite.flows
                .filter { it.status == FlowStatus.ERROR }

            PrintUtils.err(
                "${failedFlows.size}/${suite.flows.size} ${flowWord(failedFlows.size)} Failed",
                bold = true,
            )

            if (canceledFlows.isNotEmpty()) {
                PrintUtils.warn("${canceledFlows.size} ${flowWord(canceledFlows.size)} Canceled")
            }

        } else {
            val passedFlows = suite.flows
                .filter { it.status == FlowStatus.SUCCESS || it.status == FlowStatus.WARNING }


            if (passedFlows.isNotEmpty()) {
                val durationMessage = suite.duration?.let { " in $it" } ?: ""
                PrintUtils.success(
                    "${passedFlows.size}/${suite.flows.size} ${flowWord(passedFlows.size)} Passed$durationMessage",
                    bold = true,
                )

                if (canceledFlows.isNotEmpty()) {
                    PrintUtils.warn("${canceledFlows.size} ${flowWord(canceledFlows.size)} Canceled")
                }
            } else {
                println()
                PrintUtils.err("All flows were canceled")
            }
        }
        println()

        if (suite.uploadDetails != null) {
            println("==== View details in the console ====")
            PrintUtils.message(
                uploadUrl(
                    suite.uploadDetails.uploadId.toString(),
                    suite.uploadDetails.teamId,
                    suite.uploadDetails.appId,
                    suite.uploadDetails.domain,
                )
            )
            println()
        }
    }

    private fun printStatus(status: FlowStatus, cancellationReason: UploadStatus.CancellationReason?) {
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
            FlowStatus.CANCELED -> when (cancellationReason) {
                UploadStatus.CancellationReason.TIMEOUT -> "Timeout"
                UploadStatus.CancellationReason.OVERLAPPING_BENCHMARK -> "Skipped"
                UploadStatus.CancellationReason.BENCHMARK_DEPENDENCY_FAILED -> "Skipped"
                else -> "Canceled"
            }
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
        appId: String,
        domain: String = "mobile.dev",
    ) = "https://console.$domain/uploads/$uploadId?teamId=$teamId&appId=$appId"

    private fun flowWord(count: Int) = if (count == 1) "Flow" else "Flows"

    data class TestSuiteViewModel(
        val status: FlowStatus,
        val flows: List<FlowResult>,
        val duration: Duration? = null,
        val uploadDetails: UploadDetails? = null,
    ) {

        data class FlowResult(
            val name: String,
            val status: FlowStatus,
            val duration: Duration? = null,
            val error: String? = null,
            val cancellationReason: UploadStatus.CancellationReason? = null
        )

        data class UploadDetails(
            val uploadId: UUID,
            val teamId: String,
            val appId: String,
            val domain: String,
        )

        companion object {

            fun UploadStatus.toViewModel(
                uploadDetails: UploadDetails
            ) = TestSuiteViewModel(
                uploadDetails = uploadDetails,
                status = FlowStatus.from(status),
                flows = flows.map {
                    it.toViewModel()
                }
            )

            fun UploadStatus.FlowResult.toViewModel() = FlowResult(
                name = name,
                status = FlowStatus.from(status, ),
                error = errors.firstOrNull(),
                cancellationReason = cancellationReason
            )

        }

    }

}

// Helped launcher to play around with presentation
fun main() {
    val status = TestSuiteStatusView.TestSuiteViewModel(
        uploadDetails = TestSuiteStatusView.TestSuiteViewModel.UploadDetails(
            uploadId = UUID.randomUUID(),
            teamId = "teamid",
            appId = "appid",
            domain = "mobile.dev",
        ),
        status = FlowStatus.CANCELED,
        flows = listOf(
            FlowResult(
                name = "A",
                status = FlowStatus.SUCCESS,
                duration = 42.seconds,
            ),
            FlowResult(
                name = "B",
                status = FlowStatus.SUCCESS,
                duration = 231.seconds,
            ),
            FlowResult(
                name = "C",
                status = FlowStatus.CANCELED,
            )
        ),
        duration = 273.seconds,
    )

    status.flows
        .forEach {
            TestSuiteStatusView.showFlowCompletion(it)
        }

    TestSuiteStatusView.showSuiteResult(status)
}
