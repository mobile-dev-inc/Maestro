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
     * Super properties to be sent with the event
     */
    private val superProperties = SuperProperties.create()

    /**
     * Call initially just to inform user and set a default state
     */
    fun warnAndEnableAnalyticsIfNotDisable() {
        if (hasRunBefore) return
        println("Anonymous analytics enabled. To opt out, set $DISABLE_ANALYTICS_ENV_VAR environment variable to any value before running Maestro.\n")
        analyticsStateManager.saveInitialState(granted = !analyticsDisabledWithEnvVar, uuid = uuid)
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
                val groupProperties = userState.orgId?.let { orgId ->
                   mapOf(
                       "\$groups" to mapOf(
                           "company" to orgId
                       )
                   )
                } ?: emptyMap()
                val properties =
                    eventData.properties +
                    superProperties.toMap() +
                    UserProperties.fromAnalyticsState(userState).toMap() +
                    groupProperties

                // Send Event
                posthog.capture(
                    uuid,
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
            val properties = eventMap.filterKeys { it != "name" }

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
        // First, flush any pending PostHog events before shutting down threads
        flush()

        // Now shutdown PostHog to cleanup resources
        try {
            posthog.close()
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality or show errors to users
            logger.trace("Failed to close PostHog: ${e.message}", e)
        }

        // Now shutdown the executor
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
    }
}

/**
 * Data class to hold event name and properties for destructuring
 */
data class EventData(
    val eventName: String,
    val properties: Map<String, Any>
)
