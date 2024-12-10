package maestro.cli.graphics

import maestro.cli.api.ApiClient
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.view.ProgressBar
import maestro.cli.view.render
import java.io.File

class RemoteVideoRenderer : VideoRenderer {

    override fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>
    ) {
        val client = ApiClient("")

        System.err.println()
        System.err.println("@|bold ⚠\uFE0F DEPRECATION NOTICE ⚠\uFE0F\nThis method of recording will soon be deprecated and replaced with a local rendering implementation.\nTo switch to (Beta) local rendering, use \"maestro record --local ...\". This will become the default behavior in a future Maestro release.|@".render())
        System.err.println()

        val uploadProgress = ProgressBar(50)
        System.err.println("Uploading raw files for render...")
        val id = client.render(screenRecording, textFrames) { totalBytes, bytesWritten ->
            uploadProgress.set(bytesWritten.toFloat() / totalBytes)
        }
        System.err.println()

        var renderProgress: ProgressBar? = null
        var status: String? = null
        var positionInQueue: Int? = null
        while (true) {
            val state = client.getRenderState(id)

            // If new position or status, print header
            if (state.status != status || state.positionInQueue != positionInQueue) {
                status = state.status
                positionInQueue = state.positionInQueue

                if (renderProgress != null) {
                    renderProgress.set(1f)
                    System.err.println()
                }

                System.err.println()

                System.err.println("Status : ${styledStatus(state.status)}")
                if (state.positionInQueue != null) {
                    System.err.println("Position In Queue : ${state.positionInQueue}")
                }
            }

            // Add ticks to progress bar
            if (state.currentTaskProgress != null) {
                if (renderProgress == null) renderProgress = ProgressBar(50)
                renderProgress.set(state.currentTaskProgress)
            }

            // Print download url or error and return
            if (state.downloadUrl != null || state.error != null) {
                System.err.println()
                if (state.downloadUrl != null) {
                    System.err.println("@|bold Signed Download URL:|@".render())
                    System.err.println()
                    print("@|cyan,bold ${state.downloadUrl}|@".render())
                    System.err.println()
                    System.err.println()
                    System.err.println("Open the link above to download your video. If you're sharing on Twitter be sure to tag us @|bold @mobile__dev|@!".render())
                } else {
                    System.err.println("@|bold Render encountered during rendering:|@".render())
                    System.err.println(state.error)
                }
                break
            }

            Thread.sleep(2000)
        }
    }

    private fun styledStatus(status: String): String {
        val style = when (status) {
            "PENDING" -> "yellow,bold"
            "RENDERING" -> "blue,bold"
            "SUCCESS" -> "green,bold"
            else -> "bold"
        }
        return "@|$style $status|@".render()
    }
}