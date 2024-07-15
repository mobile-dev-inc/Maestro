package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.util.FileDownloader
import maestro.cli.util.PrintUtils.err
import maestro.cli.util.PrintUtils.message
import maestro.cli.view.ProgressBar
import org.rauschig.jarchivelib.ArchiverFactory
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "download-samples",
    description = [
        "Download sample apps and flows for trying out maestro without setting up your own app"
    ]
)
class DownloadSamplesCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

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
        val progressView = ProgressBar(20)

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

    companion object {

        private const val SAMPLES_URL = "https://storage.googleapis.com/mobile.dev/samples/samples.zip"

    }

}
