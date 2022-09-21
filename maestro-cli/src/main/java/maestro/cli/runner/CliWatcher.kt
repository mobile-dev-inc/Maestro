package maestro.cli.runner

import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object CliWatcher {

    fun waitForFileChangeOrEnter(fileWatcher: FileWatcher, files: List<Path>): SignalType {
        val executor = Executors.newCachedThreadPool()

        val fileChangeFuture = CompletableFuture.supplyAsync({
            fileWatcher.waitForChange(files)
            SignalType.FILE_CHANGE
        }, executor)

        val enterFuture = CompletableFuture.supplyAsync({
            interruptibleWaitForChar(System.`in`, '\n')
            SignalType.ENTER
        }, executor)

        val signalType = CompletableFuture.anyOf(fileChangeFuture, enterFuture).get() as SignalType

        executor.shutdownNow()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw InterruptedException("Timed out waiting for threads to shutdown")
        }

        return signalType
    }

    private fun interruptibleWaitForChar(inputStream: InputStream, c: Char) {
        while (true) {
            if (inputStream.available() > 0){
                if (inputStream.read().toChar() == c) {
                    return
                }
            } else {
                Thread.sleep(100)
            }
        }
    }

    enum class SignalType {
        ENTER, FILE_CHANGE
    }
}