package maestro.cli.update

import maestro.cli.api.CliVersion
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils.CLI_VERSION
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maestro.cli.util.ChangeLogUtils
import maestro.cli.util.ChangeLog
import maestro.utils.HttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

object Updates {
    private val DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory()
    private val EXECUTOR = Executors.newCachedThreadPool {
        DEFAULT_THREAD_FACTORY.newThread(it).apply { isDaemon = true }
    }

    private var future: CompletableFuture<CliVersion?>? = null
    private var changelogFuture: CompletableFuture<List<String>>? = null

    // Redirects to the release marked Latest, e.g. .../releases/tag/cli-2.12.1. Unlike the
    // GitHub REST API, it isn't subject to the 60 requests/hour unauthenticated limit.
    private const val LATEST_RELEASE_URL = "https://github.com/mobile-dev-inc/maestro/releases/latest"
    private val CLIENT by lazy {
        HttpClient.build("Updates", connectTimeout = 3.seconds, readTimeout = 3.seconds)
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Off when MAESTRO_DISABLE_UPDATE_CHECK is "true" (e.g. when installed by a package manager such as nix),
    // and on CI, where nobody reads the notice.
    internal fun isDisabled(disableUpdateCheck: String?, ciProvider: String?): Boolean =
        disableUpdateCheck?.toBoolean() == true || ciProvider != null

    private val disabled: Boolean
        get() = isDisabled(System.getenv("MAESTRO_DISABLE_UPDATE_CHECK"), CiUtils.getCiProvider())

    fun fetchUpdatesAsync() {
        if (disabled) return
        getFuture()
    }

    fun fetchChangelogAsync() {
        getChangelogFuture()
    }

    fun checkForUpdates(): CliVersion? {
        if (disabled) return null
        return try {
            getFuture().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun getChangelog(): List<String>? {
        if (disabled) return null
        return try {
            getChangelogFuture().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchUpdates(): CliVersion? {
        if (CLI_VERSION == null) {
            return null
        }

        val latestCliVersion = fetchLatestReleaseVersion() ?: return null

        return if (latestCliVersion > CLI_VERSION) {
            latestCliVersion
        } else {
            null
        }
    }

    internal fun fetchLatestReleaseVersion(url: String = LATEST_RELEASE_URL): CliVersion? {
        val request = Request.Builder().url(url).head().build()
        return CLIENT.newCall(request).execute().use { parseReleaseLocation(it.header("Location")) }
    }

    internal fun parseReleaseLocation(location: String?): CliVersion? {
        val version = location?.substringAfterLast("/tag/cli-", missingDelimiterValue = "") ?: return null
        return CliVersion.parse(version)
    }

    private fun fetchChangelog(): ChangeLog {
        // Reuse the update check's result rather than asking GitHub again.
        val version = getFuture().join()?.toString() ?: return null
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
