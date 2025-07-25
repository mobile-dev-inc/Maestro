package maestro.cli.runner

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import kotlin.io.path.absolute

class FileWatcher {

    private val watchService = FileSystems.getDefault().newWatchService()
    private val watchKeys = mutableSetOf<WatchKey>()

    fun waitForChange(files: Iterable<Path>) {
        watchKeys.forEach(WatchKey::cancel)
        watchKeys.clear()

        val paths = files.map { it.absolute() }

        paths.forEach { path ->
            val watchKey = path.parent.register(
                watchService,
                arrayOf(
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                ),
            )
            watchKeys.add(watchKey)
        }

        fun isRelevantWatchKey(watchKey: WatchKey): Boolean {
            watchKey.pollEvents().forEach { event ->
                val relativePath = event.context() as Path
                val fullPath = (watchKey.watchable() as Path).resolve(relativePath).toAbsolutePath()
                if (fullPath in paths) return true
            }
            return false
        }

        while (true) {
            val watchKey = watchService.take()
            try {
                if (watchKey.isValid) {
                    if (isRelevantWatchKey(watchKey)) {
                        break
                    }
                } else {
                    watchKeys.remove(watchKey)
                }
            } finally {
                watchKey.reset()
            }
        }
    }
}
