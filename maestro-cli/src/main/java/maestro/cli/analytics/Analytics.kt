package maestro.cli.analytics

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.posthog.java.PostHog
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.util.EnvUtils
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.String

object Analytics : AutoCloseable {
    private const val POSTHOG_API_KEY: String = "phc_XKhdIS7opUZiS58vpOqbjzgRLFpi0I6HU2g00hR7CVg"
    private const val POSTHOG_HOST: String = "https://us.i.posthog.com"
    private const val DISABLE_ANALYTICS_ENV_VAR = "MAESTRO_CLI_NO_ANALYTICS"
    private val JSON = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val apiClient = ApiClient(EnvUtils.BASE_API_URL)
    private val posthog = PostHog.Builder(POSTHOG_API_KEY).host(POSTHOG_HOST).build();


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
        val user = apiClient.getUser(token)
        val org =  apiClient.getOrg(token)

        // Update local state with user info
        val updatedAnalyticsState = analyticsStateManager.updateState(token, user, org);
        val identifyProperties = UserProperties.fromAnalyticsState(updatedAnalyticsState).toMap()

        // Send identification to PostHog
        posthog.identify(analyticsStateManager.getState().uuid, identifyProperties)
    }

    /**
     * Conditionally identify user based on current and cashed token
     */
    fun identifyUserIfNeeded() {
        // No identification needed if token is null
        val token = ApiKey.getToken() ?: return
        // No identification needed if token is same as cachedToken
        if (token == analyticsStateManager.getState().cachedToken) return
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
                if (!analyticsStateManager.getState().enabled || analyticsDisabledWithEnvVar) {
                    return@submit
                }
                identifyUserIfNeeded()

                // Include super properties in each event since PostHog Java client doesn't have register
                val eventData = convertEventToEventData(event)
                val userState = analyticsStateManager.getState()
                val properties = eventData.properties + superProperties.toMap() + UserProperties.fromAnalyticsState(userState).toMap()

                // Send Event
                posthog.capture(
                    uuid,
                    eventData.eventName,
                    properties
                )

            } catch (e: Exception) {
                // Analytics failures should never break CLI functionality
                println("Failed to track event ${event.name}: ${e.message}")
                logger.trace("Failed to track event ${event.name}: ${e.message}")
            }
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
            logger.warn("Failed to serialize event ${event.name}: ${e.message}")
            EventData(event.name, mapOf())
        }
    }

    /**
     * Close and cleanup resources
     * Ensures pending analytics events are sent before shutdown
     */
    override fun close() {
        try {
            // Shutdown executor gracefully to allow pending events to complete
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // Now shutdown PostHog to flush any remaining events
        posthog.close()
    }
}

/**
 * Data class to hold event name and properties for destructuring
 */
data class EventData(
  val eventName: String,
  val properties: Map<String, Any>
)
