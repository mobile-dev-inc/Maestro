package maestro.cli.analytics

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.util.EnvUtils
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.String

object Analytics : AutoCloseable {
    private const val POSTHOG_API_KEY: String = "phc_XKhdIS7opUZiS58vpOqbjzgRLFpi0I6HU2g00hR7CVg"
    private const val POSTHOG_HOST: String = "https://us.i.posthog.com"
    private const val DISABLE_ANALYTICS_ENV_VAR = "MAESTRO_CLI_NO_ANALYTICS"
    private val JSON = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val apiClient = ApiClient(EnvUtils.BASE_API_URL)
    private val posthog: PostHogInterface = PostHog.with(
        PostHogConfig.builder(POSTHOG_API_KEY)
            .host(POSTHOG_HOST)
            .build()
    )

    private val logger = LoggerFactory.getLogger(Analytics::class.java)
    private val analyticsStatePath: Path = EnvUtils.xdgStateHome().resolve("analytics.json")
    private val analyticsStateManager = AnalyticsStateManager(analyticsStatePath)

    // Simple executor for analytics events - following ErrorReporter pattern
    private val executor = Executors.newCachedThreadPool {
        Executors.defaultThreadFactory().newThread(it).apply { isDaemon = true }
    }

    private val eventDispatcher = EventDispatcher(
        executor = executor,
        isEnabled = { analyticsStateManager.getState().enabled && !analyticsDisabledWithEnvVar },
        readToken = ApiKey::getToken,
        identifyIfNeeded = ::identifyUserIfNeeded,
        readState = analyticsStateManager::getState,
        send = ::sendEvent,
    )

    private val analyticsDisabledWithEnvVar: Boolean
      get() = System.getenv(DISABLE_ANALYTICS_ENV_VAR) != null

    val hasRunBefore: Boolean
        get() = analyticsStateManager.hasRunBefore()

    val uuid: String
        get() = analyticsStateManager.getState().uuid


    /**
     * Super properties to be sent with the event
     */
    private val superProperties = SuperProperties.create()

    /**
     * Sanitized representation of the CLI invocation (`maestro <subcommand> --flag value ...`),
     * set once from `App.main()` via [CommandArgsSanitizer.sanitize]. When non-null, it is merged
     * into every PostHog event's properties under the key `commandStringUsed` by
     * [convertEventToEventData].
     *
     * This effectively attaches `commandStringUsed` to every event the CLI fires today:
     * - `maestro_cli_command_run`
     * - `cloud_upload_triggered`, `cloud_upload_started`, `cloud_upload_succeeded`
     * - `cloud_run_finished`
     * - `test_run_started`, `test_run_failed`, `test_run_finished`
     * - `workspace_run_started`, `workspace_run_failed`, `workspace_run_finished`
     * - Auth and record events (harmless; filter out in PostHog queries if undesired).
     */
    @Volatile
    var commandString: String? = null

    /**
     * Call initially just to inform user and set a default state
     */
    fun warnAndEnableAnalyticsIfNotDisable() {
        if (hasRunBefore) return
        val analyticsShouldBeEnabled = !analyticsDisabledWithEnvVar
        if (analyticsShouldBeEnabled)
            println("Anonymous analytics enabled. To opt out, set $DISABLE_ANALYTICS_ENV_VAR environment variable to any value before running Maestro.\n")
        analyticsStateManager.saveInitialState(granted = analyticsShouldBeEnabled, uuid = uuid)
    }

    /**
     * Identify user in PostHog and update local state.
     *
     * This function:
     * 1. Sends user identification to PostHog analytics
     * 2. Updates local analytics state with user info
     * 3. Tracks login event for analytics
     *
     * Should only be called when user identity changes (login/logout).
     */
    fun identifyAndUpdateState(token: String) {
        try {
            val user = apiClient.getUser(token)
            val org =  apiClient.getOrg(token)

            // Update local state with user info
            val updatedAnalyticsState = analyticsStateManager.updateState(token, user, org)
            val identifyProperties = UserProperties.fromAnalyticsState(updatedAnalyticsState).toMap()

            // Send identification to PostHog
            posthog.identify(analyticsStateManager.getState().uuid, identifyProperties)
            // Track user authentication event
            val isFirstAuth = analyticsStateManager.getState().cachedToken == null
            eventDispatcher.dispatch(UserAuthenticatedEvent(
                isFirstAuth = isFirstAuth,
                authMethod = "oauth"
            ), token)
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to identify user: ${e.message}", e)
        }
    }

    /**
     * Conditionally identify user based on current and cashed token
     */
    private fun identifyUserIfNeeded(token: String) {
        val cachedToken = analyticsStateManager.getState().cachedToken
        // No identification needed if token is same as cachedToken
        if (!cachedToken.isNullOrEmpty() && (token == cachedToken)) return
        // Else Update identification
        identifyAndUpdateState(token)
    }

    /**
     * Track events asynchronously to prevent blocking CLI operations
     * Use this for important events like authentication, errors, test results, etc.
     * This method is "fire and forget" - apart from reading the token, the work runs in the background
     */
    fun trackEvent(event: PostHogEvent) {
        eventDispatcher.dispatch(event)
    }

    private fun sendEvent(event: PostHogEvent, identityProperties: Map<String, Any>) {
        // Include super properties in each event since PostHog Java client doesn't have register
        val eventData = convertEventToEventData(event)
        val properties =
            eventData.properties +
            superProperties.toMap() +
            identityProperties

        // Send Event
        posthog.capture(
            uuid,
            eventData.eventName,
            properties
        )
    }

    /**
     * Flush pending PostHog events immediately
     * Use this when you need to ensure events are sent before continuing
     */
    fun flush() {
        try {
            posthog.flush()
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to flush PostHog: ${e.message}", e)
        }
    }

    /**
     * Convert a PostHogEvent to EventData with eventName and properties separated
     * This allows for clean destructuring in the calling code
     */
    private fun convertEventToEventData(event: PostHogEvent): EventData {
        return try {
            // Use Jackson to convert the data class to a Map
            val jsonString = JSON.writeValueAsString(event)
            val eventMap = JSON.readValue(jsonString, Map::class.java) as Map<String, Any>

            // Extract the name and create properties without it
            val eventName = event.name
            val baseProperties = eventMap.filterKeys { it != "name" }

            // Merge the sanitized CLI invocation as a super-property on every event so we have
            // a queryable signal for deprecated/legacy flag usage without per-flag instrumentation.
            val properties = commandString?.let { baseProperties + ("commandStringUsed" to it) }
                ?: baseProperties

            EventData(eventName, properties)
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to serialize event ${event.name}: ${e.message}", e)
            EventData(event.name, mapOf())
        }
    }

   /**
    * Close and cleanup resources
    * Ensures pending analytics events are sent before shutdown
    */
    override fun close() {
        // First, let tracked events reach PostHog's queue: flushing before this loses them
        try {
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                // Analytics failures should never break CLI functionality or show errors to users
                logger.trace("Analytics executor did not shutdown gracefully, forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // Then flush pending PostHog events
        flush()

        // Now shutdown PostHog to cleanup resources
        try {
            posthog.close()
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to close PostHog: ${e.message}", e)
        }
    }
}

/**
 * Data class to hold event name and properties for destructuring
 */
data class EventData(
    val eventName: String,
    val properties: Map<String, Any>
)

internal class EventDispatcher(
    private val executor: Executor,
    private val isEnabled: () -> Boolean,
    private val readToken: () -> String?,
    private val identifyIfNeeded: (String) -> Unit,
    private val readState: () -> AnalyticsState,
    private val send: (PostHogEvent, Map<String, Any>) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(EventDispatcher::class.java)

    /**
     * [token] defaults to a read on the caller's thread: `maestro logout` deletes it right after
     * tracking, and its events must still count as logged in.
     */
    fun dispatch(event: PostHogEvent, token: String? = readTokenSafely()) {
        executor.execute {
            try {
                if (!isEnabled()) return@execute

                token?.let(identifyIfNeeded)
                send(event, identityProperties(readState(), token))
            } catch (e: Throwable) {
                // Analytics failures should never break CLI functionality
                logger.trace("Failed to track event ${event.name}: ${e.message}", e)
            }
        }
    }

    private fun readTokenSafely(): String? = try {
        readToken()
    } catch (e: Exception) {
        logger.trace("Failed to read token: ${e.message}", e)
        null
    }
}

/**
 * Properties that tie an event to a person. Having a token is what makes the user logged in:
 * logout leaves the user and org in [state], and a failed identify may not have written them.
 * Events without a token get no person profile and no company group.
 */
private fun identityProperties(state: AnalyticsState, token: String?): Map<String, Any> {
    val userProperties = UserProperties.fromAnalyticsState(state).toMap()
    if (token.isNullOrBlank()) return userProperties + ("\$process_person_profile" to false)

    val groupProperties = state.orgId?.let { orgId ->
        mapOf("\$groups" to mapOf("company" to orgId))
    } ?: emptyMap()
    return userProperties + groupProperties
}
