package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.util.FileDownloader
import maestro.cli.util.PrintUtils.err
import maestro.cli.util.PrintUtils.message
import org.fusesource.jansi.Ansi
import org.rauschig.jarchivelib.ArchiverFactory
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "download-samples",
)
class DownloadSamplesCommand : Callable<Int> {

    @Option(names = ["-o", "--output"], description = ["Output directory"])
    private var outputDirectory: File? = null

    override fun call(): Int {
        val folder = ensureSamplesFolder()
        val samplesFile = File("maestro-samples.zip")

        return runBlocking {
            try {
                downloadSamplesZip(samplesFile)

                val archiver = ArchiverFactory.createArchiver(samplesFile)
                archiver.extract(samplesFile, folder)

                message("✅ Samples downloaded to $folder/")
                return@runBlocking 0
            } catch (e: Exception) {
                err(e.message ?: "Error downloading samples: $e")
                return@runBlocking 1
            } finally {
                samplesFile.delete()
            }
        }
    }

    private suspend fun downloadSamplesZip(file: File) {
        val progressView = DownloadProgress(20)

        FileDownloader
            .downloadFile(
                SAMPLES_URL,
                file
            ).collect {
                when (it) {
                    is FileDownloader.DownloadResult.Success -> {
                        // Do nothing
                    }
                    is FileDownloader.DownloadResult.Error -> {
                        throw it.cause ?: error(it.message)
                    }
                    is FileDownloader.DownloadResult.Progress -> {
                        progressView.set(it.progress)
                    }
                }
            }
    }

    private fun ensureSamplesFolder(): File {
        val outputDir = outputDirectory
            ?: File("samples")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        return outputDir
    }

    class DownloadProgress(private val width: Int) {

        private var progressWidth: Int? = null

        fun set(progress: Float) {
            val progressWidth = (progress * width).toInt()
            if (progressWidth == this.progressWidth) return
            this.progressWidth = progressWidth
            val ansi = Ansi.ansi()
            ansi.cursorToColumn(0)
            ansi.fgCyan()
            repeat(progressWidth) { ansi.a("█") }
            repeat(width - progressWidth) { ansi.a("░") }
            print(ansi)
        }
    }

    companion object {

        private const val SAMPLES_URL = "https://storage.googleapis.com/mobile.dev/samples/samples.zip"

    }

}