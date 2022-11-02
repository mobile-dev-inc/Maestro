package maestro.cli.cloud

import maestro.cli.api.UploadStatus
import maestro.cli.util.PrintUtils
import org.fusesource.jansi.Ansi
import java.util.UUID

object UploadStatusView {

    fun showFlowCompletion(result: UploadStatus.FlowResult) {
        printStatus(result.status)
        print(" ${result.name}")

        if (result.status == UploadStatus.Status.WARNING) {
            print(
                Ansi.ansi()
                    .fgYellow()
                    .render(" (Warning)")
                    .fgDefault()
            )
        }

        println()
    }

    fun showUploadResult(
        upload: UploadStatus,
        teamId: String,
        appId: String,
    ) {
        if (upload.status == UploadStatus.Status.ERROR) {
            val failedFlows = upload.flows
                .filter { it.status == UploadStatus.Status.ERROR }

            PrintUtils.err(
                "${failedFlows.size}/${upload.flows.size} ${flowWord(failedFlows.size)} Failed",
                bold = true,
            )
        } else {
            val passedFlows = upload.flows
                .filter { it.status == UploadStatus.Status.SUCCESS || it.status == UploadStatus.Status.WARNING }

            val canceledFlows = upload.flows
                .filter { it.status == UploadStatus.Status.CANCELED }

            if (passedFlows.isNotEmpty()) {
                PrintUtils.success(
                    "${passedFlows.size}/${upload.flows.size} ${flowWord(passedFlows.size)} Passed",
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

        println("==== View details in the console ====")
        PrintUtils.message(uploadUrl(upload.uploadId.toString(), teamId, appId))
        println()
    }

    private fun printStatus(status: UploadStatus.Status) {
        val color = when (status) {
            UploadStatus.Status.SUCCESS,
            UploadStatus.Status.WARNING -> Ansi.Color.GREEN
            UploadStatus.Status.ERROR -> Ansi.Color.RED
            else -> Ansi.Color.DEFAULT
        }
        val title = when (status) {
            UploadStatus.Status.SUCCESS,
            UploadStatus.Status.WARNING -> "Passed"
            UploadStatus.Status.ERROR -> "Failed"
            UploadStatus.Status.PENDING -> "Pending"
            UploadStatus.Status.RUNNING -> "Running"
            UploadStatus.Status.CANCELED -> "Canceled"
        }

        print(
            Ansi.ansi()
                .fgBright(color)
                .render("[$title]")
                .fgDefault()
        )
    }

    fun uploadUrl(uploadId: String, teamId: String, appId: String) = "https://console.mobile.dev/uploads/$uploadId?teamId=$teamId&appId=$appId"

    private fun flowWord(count: Int) = if (count == 1) "Flow" else "Flows"

}

// Helped launcher to play around with presentation
fun main() {
    val status = UploadStatus(
        uploadId = UUID.randomUUID(),
        status = UploadStatus.Status.CANCELED,
        completed = true,
        flows = listOf(
            UploadStatus.FlowResult(
                name = "A",
                status = UploadStatus.Status.SUCCESS,
            ),
            UploadStatus.FlowResult(
                name = "B",
                status = UploadStatus.Status.SUCCESS,
            ),
            UploadStatus.FlowResult(
                name = "C",
                status = UploadStatus.Status.CANCELED,
            )
        )
    )

    status.flows
        .forEach {
            UploadStatusView.showFlowCompletion(it)
        }

    UploadStatusView.showUploadResult(status, "teamId", "appId")
}
