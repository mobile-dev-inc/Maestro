package maestro.cli.update

import maestro.MaestroException
import maestro.cli.api.ApiClient
import maestro.cli.api.CliVersion
import maestro.cli.util.EnvUtils
import maestro.cli.util.EnvUtils.CLI_VERSION
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maestro.cli.util.ChangeLogUtils
import maestro.cli.util.ChangeLog
import maestro.cli.view.blue
import maestro.cli.view.bold

object Updates {
    private val DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory()
    private val EXECUTOR = Executors.newCachedThreadPool {
        DEFAULT_THREAD_FACTORY.newThread(it).apply { isDaemon = true }
    }

    private var future: CompletableFuture<CliVersion?>? = null
    private var changelogFuture: CompletableFuture<List<String>>? = null

    fun fetchUpdatesAsync() {
        getFuture()
    }

    fun showJavaUpdateMessage() {
        val currentJavaVersion = EnvUtils.getJavaVersion()

        if (currentJavaVersion < 17) {
            println(
                """
                    ${"Java Version Error".bold().blue()}:
                    
                    This application requires ${"Java 17 or greater".bold()}.
                    Your current Java version is ${currentJavaVersion}.
                    
                    Please update your Java installation to continue.
                    """.trimIndent()
            )
            throw MaestroException.UnsupportedJavaVersion("Maestro requires Java 17 or greater.")
        }
    }

    fun fetchChangelogAsync() {
        getChangelogFuture()
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

    fun getChangelog(): List<String>? {
        // Disable update check, when MAESTRO_DISABLE_UPDATE_CHECK is set to "true" e.g. when installed by a package manager. e.g. nix
        if (System.getenv("MAESTRO_DISABLE_UPDATE_CHECK")?.toBoolean() == true) {
            return null
        }
        return try {
            getChangelogFuture().get(3, TimeUnit.SECONDS)
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

    private fun fetchChangelog(): ChangeLog {
        if (CLI_VERSION == null) {
            return null
        }
        val version = fetchUpdates()?.toString() ?: return null
        val content = ChangeLogUtils.fetchContent()
        return ChangeLogUtils.formatBody(content, version)
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

    @Synchronized
    private fun getChangelogFuture(): CompletableFuture<List<String>> {
        var changelogFuture = this.changelogFuture
        if (changelogFuture == null) {
            changelogFuture = CompletableFuture.supplyAsync(this::fetchChangelog, EXECUTOR)!!
            this.changelogFuture = changelogFuture
        }
        return changelogFuture
    }
}
