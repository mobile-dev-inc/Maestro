package maestro.cli.view

import maestro.cli.api.UploadStatus
import maestro.cli.model.FlowStatus
import maestro.cli.util.PrintUtils
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel.FlowResult
import maestro.cli.view.TestSuiteStatusView.uploadUrl
import org.fusesource.jansi.Ansi
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object TestSuiteStatusView {

    fun showFlowCompletion(result: FlowResult) {
        val shardPrefix = result.shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()
        print(Ansi.ansi().fgCyan().render(shardPrefix).fgDefault())

        printStatus(result.status, result.cancellationReason)

        val durationString = result.duration?.let { " ($it)" }.orEmpty()
        print(" ${result.name}$durationString")

        if (result.status == FlowStatus.ERROR && result.error != null) {
            val error = " (${result.error})"
            print(Ansi.ansi().fgRed().render(error).fgDefault())
        }
        else if (result.status == FlowStatus.WARNING) {
            val warning = " (Warning)"
            print(Ansi.ansi().fgYellow().render(warning).fgDefault())
        }
        println()
    }

    fun showSuiteResult(
        suite: TestSuiteViewModel,
        uploadUrl: String,
    ) {
        val hasError = suite.flows.find { it.status == FlowStatus.ERROR } != null
        val canceledFlows = suite.flows
            .filter { it.status == FlowStatus.CANCELED }
        val shardPrefix = suite.shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

        if (suite.status == FlowStatus.ERROR || hasError) {
            val failedFlows = suite.flows
                .filter { it.status == FlowStatus.ERROR }

            PrintUtils.err(
                "${shardPrefix}${failedFlows.size}/${suite.flows.size} ${flowWord(failedFlows.size)} Failed",
                bold = true,
            )

            if (canceledFlows.isNotEmpty()) {
                PrintUtils.warn("${shardPrefix}${canceledFlows.size} ${flowWord(canceledFlows.size)} Canceled")
            }

        } else {
            val passedFlows = suite.flows
                .filter { it.status == FlowStatus.SUCCESS || it.status == FlowStatus.WARNING }


            if (passedFlows.isNotEmpty()) {
                val durationMessage = suite.duration?.let { " in $it" } ?: ""
                PrintUtils.success(
                    "${shardPrefix}${passedFlows.size}/${suite.flows.size} ${flowWord(passedFlows.size)} Passed$durationMessage",
                    bold = true,
                )

                if (canceledFlows.isNotEmpty()) {
                    PrintUtils.warn("${shardPrefix}${canceledFlows.size} ${flowWord(canceledFlows.size)} Canceled")
                }
            } else {
                println()
                PrintUtils.err("${shardPrefix}All flows were canceled")
            }
        }
        println()

        if (suite.uploadDetails != null) {
            println("==== View details in the console ====")
            PrintUtils.message(uploadUrl)
            println()
        }
    }

    private fun printStatus(status: FlowStatus, cancellationReason: UploadStatus.CancellationReason?) {
        val color = when (status) {
            FlowStatus.SUCCESS,
            FlowStatus.WARNING -> Ansi.Color.GREEN
            FlowStatus.ERROR -> Ansi.Color.RED
            FlowStatus.STOPPED -> Ansi.Color.RED
            else -> Ansi.Color.DEFAULT
        }
        val title = when (status) {
            FlowStatus.SUCCESS,
            FlowStatus.WARNING -> "Passed"
            FlowStatus.ERROR -> "Failed"
            FlowStatus.PENDING -> "Pending"
            FlowStatus.RUNNING -> "Running"
            FlowStatus.STOPPED -> "Stopped"
            FlowStatus.PREPARING -> "Preparing Device"
            FlowStatus.INSTALLING -> "Installing App"
            FlowStatus.CANCELED -> when (cancellationReason) {
                UploadStatus.CancellationReason.TIMEOUT -> "Timeout"
                UploadStatus.CancellationReason.OVERLAPPING_BENCHMARK -> "Skipped"
                UploadStatus.CancellationReason.BENCHMARK_DEPENDENCY_FAILED -> "Skipped"
                UploadStatus.CancellationReason.CANCELED_BY_USER -> "Canceled by user"
                UploadStatus.CancellationReason.RUN_EXPIRED -> "Run expired"
                else -> "Canceled (unknown reason)"
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
        projectId: String,
        appId: String,
        uploadId: String,
        domain: String = ""
    ): String {
        return if (domain.contains("localhost")) {
            "http://localhost:3000/project/$projectId/maestro-test/app/$appId/upload/$uploadId"
        } else {
            "https://app.maestro.dev/project/$projectId/maestro-test/app/$appId/upload/$uploadId"
        }
    }

    private fun flowWord(count: Int) = if (count == 1) "Flow" else "Flows"

    data class TestSuiteViewModel(
        val status: FlowStatus,
        val flows: List<FlowResult>,
        val duration: Duration? = null,
        val shardIndex: Int? = null,
        val uploadDetails: UploadDetails? = null,
    ) {

        data class FlowResult(
            val name: String,
            val status: FlowStatus,
            val duration: Duration? = null,
            val error: String? = null,
            val shardIndex: Int? = null,
            val cancellationReason: UploadStatus.CancellationReason? = null
        )

        data class UploadDetails(
            val uploadId: String,
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

            fun UploadStatus.FlowResult.toViewModel(
                duration: Duration? = null
            ) = FlowResult(
                name = name,
                status = status,
                error = errors.firstOrNull(),
                cancellationReason = cancellationReason,
                duration = duration
            )

        }

    }

}

// Helper launcher to play around with presentation
fun main() {
    val uploadDetails = TestSuiteStatusView.TestSuiteViewModel.UploadDetails(
        uploadId = UUID.randomUUID().toString(),
        appId = "appid",
        domain = "mobile.dev",
    )
    val status = TestSuiteStatusView.TestSuiteViewModel(
        uploadDetails = uploadDetails,
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

    val uploadUrl = uploadUrl(
        uploadDetails.uploadId.toString(),
        "teamid",
        uploadDetails.appId,
        uploadDetails.domain,
    )
    TestSuiteStatusView.showSuiteResult(status, uploadUrl)
}
