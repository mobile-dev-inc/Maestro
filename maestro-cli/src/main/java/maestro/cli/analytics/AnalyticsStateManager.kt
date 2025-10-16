package maestro.cli.analytics

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.cli.api.OrgResponse
import maestro.cli.api.UserResponse
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.String
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsState(
  val uuid: String,
  val enabled: Boolean,
  val cachedToken: String? = null,
  val lastUploadedForCLI: String? = null,
  @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") val lastUploadedTime: Instant?,
  val email: String? = null,
  val user_id: String? = null,
  val name: String? = null,
  val workOSOrgId: String? = null,
  val orgId: String? = null,
  val orgName: String? = null,
  val orgPlan: String? = null,
  val orgTrialExpiresOn: String? = null,
)

/**
 * Manages analytics state persistence and caching.
 * Separated from Analytics object to improve separation of concerns.
 */
class AnalyticsStateManager(
    private val analyticsStatePath: Path
) {
    private val logger = LoggerFactory.getLogger(AnalyticsStateManager::class.java)
    
    private val JSON = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private var _analyticsState: AnalyticsState? = null

    fun getState(): AnalyticsState {
        if (_analyticsState == null) {
            _analyticsState = loadState()
        }
        return _analyticsState!!
    }

    fun hasRunBefore(): Boolean {
        return analyticsStatePath.exists()
    }

    fun updateState(
        token: String,
        user: UserResponse,
        org: OrgResponse,
    ): AnalyticsState {
        val currentState = getState()
        val updatedState = currentState.copy(
            cachedToken = token,
            lastUploadedForCLI = EnvUtils.CLI_VERSION?.toString(),
            lastUploadedTime = Instant.now(),
            user_id = user.id,
            email = user.email,
            name = user.name,
            workOSOrgId = user.workOSOrgId,
            orgId = org.id,
            orgName = org.name,
            orgPlan = org.metadata?.get("pricing_plan"),
            orgTrialExpiresOn = org.metadata?.get("trial_expires_on")
        )
        saveState(updatedState)
        return updatedState
    }

    fun saveInitialState(
        granted: Boolean,
        uuid: String? = null,
    ): AnalyticsState {
        val state = AnalyticsState(
          uuid = uuid ?: generateUUID(),
          enabled = granted,
          lastUploadedTime = null
        )
        saveState(state)
        return state
    }

    private fun saveState(state: AnalyticsState) {
        val stateJson = JSON.writeValueAsString(state)
        analyticsStatePath.parent.toFile().mkdirs()
        analyticsStatePath.writeText(stateJson + "\n")
        logger.trace("Saved analytics to {}, value: {}", analyticsStatePath, stateJson)
        
        // Refresh the cached state
        _analyticsState = state
    }

    private fun loadState(): AnalyticsState {
        return try {
            if (analyticsStatePath.exists()) {
                JSON.readValue(analyticsStatePath.readText())
            } else {
                createDefaultState()
            }
        } catch (e: Exception) {
            logger.warn("Failed to read analytics state: ${e.message}. Using default.")
            createDefaultState()
        }
    }

    private fun createDefaultState(): AnalyticsState {
        return AnalyticsState(
          uuid = generateUUID(),
          enabled = false,
          lastUploadedTime = null,
        )
    }

    private fun generateUUID(): String {
        return CiUtils.getCiProvider() ?: UUID.randomUUID().toString()
    }
}
