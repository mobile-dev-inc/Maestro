package maestro.cli.update

import maestro.cli.api.ApiClient
import maestro.cli.api.CliVersion
import maestro.cli.util.EnvUtils
import maestro.cli.util.EnvUtils.CLI_VERSION
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Updates {
    private val DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory()
    private val EXECUTOR = Executors.newCachedThreadPool {
        DEFAULT_THREAD_FACTORY.newThread(it).apply { isDaemon = true }
    }

    private var future: CompletableFuture<CliVersion?>? = null

    fun fetchUpdatesAsync() {
        getFuture()
    }

    fun checkForUpdates(): CliVersion? {
        // Disable update check, when MAESTRO_DISABLE_UPDATE_CHECK is set to "true" e.g. when installed by a package manager. e.g. nix
        if (System.getenv("MAESTRO_DISABLE_UPDATE_CHECK")?.toBoolean() == true) {
            return null
        }
        return try {
            getFuture().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        }
    }

    private fun fetchUpdates(): CliVersion? {
        if (CLI_VERSION == null) {
            return null
        }

        val latestCliVersion = ApiClient(EnvUtils.BASE_API_URL).getLatestCliVersion()

        return if (latestCliVersion > CLI_VERSION) {
            latestCliVersion
        } else {
            null
        }
    }

    @Synchronized
    private fun getFuture(): CompletableFuture<CliVersion?> {
        var future = this.future
        if (future == null) {
            future = CompletableFuture.supplyAsync(this::fetchUpdates, EXECUTOR)!!
            this.future = future
        }
        return future
    }
}
