package maestro.cli.analytics

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import org.slf4j.LoggerFactory
import java.nio.file.Path
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

    private val analyticsDisabledWithEnvVar: Boolean
      get() = System.getenv(DISABLE_ANALYTICS_ENV_VAR) != null

    val hasRunBefore: Boolean
        get() = analyticsStateManager.hasRunBefore()

    val uuid: String
        get() = analyticsStateManager.getState().uuid

    /**
     * True only when a human has authenticated this machine via `maestro login` (cached auth
     * token file) and we're not running on CI. An API key provided via MAESTRO_CLOUD_API_KEY
     * (an automation/CI credential) deliberately does not count: those runs stay anonymous,
     * with org attribution via the `$groups` property only.
     *
     * Gates every identified-analytics behavior: capturing under the user id instead of the
     * machine uuid, attaching user properties to events, and the `$identify` merge on login.
     */
    private val isInteractiveLogin: Boolean
        get() = ApiKey.getCachedAuthToken() != null && CiUtils.getCiProvider() == null


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

            // Compute before updateState overwrites cachedToken
            val isFirstAuth = analyticsStateManager.getState().cachedToken == null

            // Update local state with user info
            val updatedAnalyticsState = analyticsStateManager.updateState(token, user, org)

            if (isInteractiveLogin) {
                val identifyProperties = UserProperties.fromAnalyticsState(updatedAnalyticsState).toMap()
                // Real identity merge: an `$identify` event with `$anon_distinct_id` folds this
                // machine's anonymous history (captured under the local uuid) into the person
                // keyed by user.id — the same distinct_id the web app identifies with, so CLI
                // and web activity resolve to one person. Note posthog.identify() alone cannot
                // do this: without $anon_distinct_id, ingestion only $set's person properties
                // and never marks the person identified.
                // Never sent on CI (isInteractiveLogin): the CI "uuid" is a shared provider
                // slug ("github", ...) and merging it would graft unrelated runs onto a person.
                posthog.capture(
                    user.id,
                    "\$identify",
                    mapOf(
                        "\$anon_distinct_id" to uuid,
                        "\$set" to identifyProperties,
                    )
                )
            }

            // Track user authentication event
            trackEvent(UserAuthenticatedEvent(
                isFirstAuth = isFirstAuth,
                authMethod = "oauth"
            ))
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to identify user: ${e.message}", e)
        }
    }

    /**
     * Clear locally-persisted user/org identity. Call on logout so subsequent events
     * fall back to the anonymous path instead of carrying a stale identity.
     */
    fun clearIdentity() {
        analyticsStateManager.clearUserState()
    }

    /**
     * Conditionally identify user based on current and cashed token
     */
    fun identifyUserIfNeeded() {
        // No identification needed if token is null
        val token = ApiKey.getToken() ?: return
        val cachedToken = analyticsStateManager.getState().cachedToken
        // No identification needed if token is same as cachedToken
        if (!cachedToken.isNullOrEmpty() && (token == cachedToken)) return
        // Else Update identification
        identifyAndUpdateState(token)
    }

    /**
     * Track events asynchronously to prevent blocking CLI operations
     * Use this for important events like authentication, errors, test results, etc.
     * This method is "fire and forget" - it will never block the calling thread
     */
    fun trackEvent(event: PostHogEvent) {
        executor.submit {
            try {
                if (!analyticsStateManager.getState().enabled || analyticsDisabledWithEnvVar) return@submit

                identifyUserIfNeeded()

                // Include super properties in each event since PostHog Java client doesn't have register
                val eventData = convertEventToEventData(event)
                val userState = analyticsStateManager.getState()
                val identified = isInteractiveLogin && userState.user_id != null

                // Org attribution applies to both identified and anonymous events (e.g. CI
                // runs authenticated with an org API key are attributed to the org, not a person)
                val groupProperties = userState.orgId?.let { orgId ->
                   mapOf(
                       "\$groups" to mapOf(
                           "company" to orgId
                       )
                   )
                } ?: emptyMap()

                val identityProperties = if (identified) {
                    UserProperties.fromAnalyticsState(userState).toMap()
                } else {
                    // Anonymous event: no person profile is created and the event is billed at
                    // the anonymous rate (person_mode 'propertyless' instead of 'full').
                    mapOf("\$process_person_profile" to false)
                }

                val properties =
                    eventData.properties +
                    superProperties.toMap() +
                    identityProperties +
                    groupProperties

                // Identified events are captured under the stable user id (shared with the web
                // app, whose $identify merge links it to this machine's uuid history); anonymous
                // events stay under the machine uuid (or CI provider slug on CI).
                posthog.capture(
                    if (identified) userState.user_id!! else uuid,
                    eventData.eventName,
                    properties
                )
            } catch (e: Exception) {
                // Analytics failures should never break CLI functionality
                logger.trace("Failed to track event ${event.name}: ${e.message}", e)
            }
        }
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
        // Order matters here (verified empirically):
        // 1. Drain the executor FIRST — a trackEvent task may still be running (e.g. the
        //    lazy identify path makes two API calls before enqueueing its PostHog events).
        //    Flushing before the task finishes means its events enqueue after the flush and
        //    get silently dropped by posthog.close().
        try {
            executor.shutdown()
            if (!executor.awaitTermination(4, TimeUnit.SECONDS)) {
                // Analytics failures should never break CLI functionality or show errors to users
                logger.trace("Analytics executor did not shutdown gracefully, forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // 2. Flush queued PostHog events. The client drops the tail of the queue when close()
        //    follows a single flush() too closely (observed empirically, even with a delay
        //    after one flush); a second flush after a short pause drains the tail reliably.
        flush()
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        flush()

        // 3. Shutdown PostHog to cleanup resources
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
