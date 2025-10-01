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
    fun identifyAndUpdateState(userId: String, email: String, name: String, workOSOrgId: String? = null,) {
        // Send identification to PostHog
        posthog.identify(analyticsStateManager.getState().uuid, mapOf<String, String?>(
            "user_id" to userId,
            "email" to email,
            "name" to name,
            "workOsOrgId" to workOSOrgId
        ))

        // Update local state with user info
         analyticsStateManager.updateState(
            userId = userId,
            email = email,
            name = name,
            workOSOrgId = workOSOrgId,
         )
    }

    /**
     * Track events with optional PostHog-level deduplication
     * Use this for important events like authentication, errors, test results, etc.
     * For CLI runs and other repeatable events, set enableDeduplication = true
     */
    fun trackEvent(event: PostHogEvent) {
        try {
            if (!analyticsStateManager.getState().enabled || analyticsDisabledWithEnvVar) return

            // If user is not set & token exist -> Identify the user
            if (analyticsStateManager.getState().email == null) {
                val token = ApiKey.getToken()
                if (token != null) {
                    val user = apiClient.getUser(token)
                    identifyAndUpdateState(userId = user.id, email = user.email, name = user.name, workOSOrgId = user.workOSOrgId)
                }
            }

            // Include super properties in each event since PostHog Java client doesn't have register
            val eventData = convertEventToEventData(event)
            val eventWithSuperPropertiesAndUserData = addSuperPropertiesAndUserData(
                eventData.properties
            )
            
            // Send Event
            posthog.capture(uuid, eventData.eventName, eventWithSuperPropertiesAndUserData)
        } catch (e: Exception) {
            // Analytics failures should never break CLI functionality
            logger.trace("Failed to track event ${event.name}: ${e.message}")
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
      * Add super properties and user data to event properties
      */
     private fun addSuperPropertiesAndUserData(eventProperties: Map<String, Any>): Map<String, Any> {
         val userData = analyticsStateManager.getState()
         val userProperties = UserProperties.fromAnalyticsState(userData)

         return eventProperties + superProperties.toMap() + userProperties.toMap()
     }

    // Get user from API

    override fun close() {
        posthog.shutdown()
    }
}

/**
 * Data class to hold event name and properties for destructuring
 */
data class EventData(
  val eventName: String,
  val properties: Map<String, Any>
)
