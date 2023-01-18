package maestro.cli.update

import maestro.cli.api.ApiClient
import maestro.cli.api.CliVersion
import maestro.cli.util.CiUtils
import maestro.cli.view.red
import java.nio.file.Paths
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object Updates {

    val OS_NAME: String = System.getProperty("os.name")
    val OS_ARCH: String = System.getProperty("os.arch")
    val DEVICE_UUID: String
    val CLI_VERSION: CliVersion? = getVersion().apply {
        if (this == null) {
            System.err.println("\nWarning: Failed to parse current version".red())
        }
    }

    const val BASE_API_URL = "https://api.mobile.dev"
    private val FRESH_INSTALL: Boolean

    private val DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory()
    private val EXECUTOR = Executors.newCachedThreadPool {
        DEFAULT_THREAD_FACTORY.newThread(it).apply { isDaemon = true }
    }

    private var future: CompletableFuture<CliVersion?>? = null

    init {
        val uuidPath = Paths.get(System.getProperty("user.home"), ".maestro", "uuid")
        FRESH_INSTALL = if (uuidPath.exists()) {
            false
        } else {
            uuidPath.parent.createDirectories()
            uuidPath.writeText(generateUUID())
            true
        }
        DEVICE_UUID = uuidPath.readText()
    }

    private fun generateUUID(): String {
        return CiUtils.getCiProvider() ?: UUID.randomUUID().toString()
    }

    fun fetchUpdatesAsync() {
        getFuture()
    }

    fun checkForUpdates(): CliVersion? {
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

        val latestCliVersion = ApiClient(BASE_API_URL).getLatestCliVersion(
            freshInstall = FRESH_INSTALL,
        )

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

    private fun getVersion(): CliVersion? {
        val props = try {
            Updates::class.java.classLoader.getResourceAsStream("version.properties").use {
                Properties().apply { load(it) }
            }
        } catch (e: Exception) {
            return null
        }

        val versionString = props["version"] as? String ?: return null

        return CliVersion.parse(versionString)
    }
}
