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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import kotlin.String
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsState(
    val uuid: String,
    val enabled: Boolean,
    // Bumped when the anonymous-id scheme changes; states below the current version get their
    // uuid rotated on load. Defaults to 1 so files written by older CLIs read as pre-rotation.
    val analyticsSchemaVersion: Int = 1,
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
    // Org status properties
    val orgStatus: OrgStatus? = null,
    val currentPlan: OrgPlans? = null,
    val isInTrial: Boolean? = null,
    val daysUntilTrialExpiry: Int? = null,
    val daysUntilGracePeriodExpiry: Int? = null
)

/**
 * Manages analytics state persistence and caching.
 * Separated from Analytics object to improve separation of concerns.
 */
class AnalyticsStateManager(
    private val analyticsStatePath: Path
) {
    companion object {
        // v2: anonymous-by-default analytics — anonymous ids rotated so they carry no
        // pre-existing person profile (see migrateIfNeeded)
        private const val CURRENT_SCHEMA_VERSION = 2
    }

    private val logger = LoggerFactory.getLogger(AnalyticsStateManager::class.java)
    
    private val JSON = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    // Written from the main thread (login/logout) and read from the analytics executor threads.
    @Volatile
    private var _analyticsState: AnalyticsState? = null

    fun getState(): AnalyticsState {
        // Synchronized: the first call may load + migrate (rotating the uuid); two threads
        // racing that check-then-act would each mint a different uuid.
        return _analyticsState ?: synchronized(this) {
            _analyticsState ?: loadState().also { _analyticsState = it }
        }
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
        ).addOrgStatusProperties(org)
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
          analyticsSchemaVersion = CURRENT_SCHEMA_VERSION,
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
        val state = try {
            if (analyticsStatePath.exists()) {
                JSON.readValue<AnalyticsState>(analyticsStatePath.readText())
            } else {
                createDefaultState()
            }
        } catch (e: Exception) {
            logger.warn("Failed to read analytics state: ${e.message}. Using default.")
            createDefaultState()
        }
        return migrateIfNeeded(state)
    }

    /**
     * Rotate the anonymous id once when adopting the anonymous-by-default analytics scheme.
     *
     * PostHog upgrades anonymous events ("$process_person_profile": false) to identified billing
     * (person_mode 'force_upgrade') whenever the distinct_id already has a person profile — and
     * every uuid that was active before this scheme has one, because all events used to be
     * captured with full person processing. A fresh uuid has no person profile, and anonymous
     * events never create one, so rotated ids stay on the anonymous rate permanently.
     */
    private fun migrateIfNeeded(state: AnalyticsState): AnalyticsState {
        if (state.analyticsSchemaVersion >= CURRENT_SCHEMA_VERSION) return state
        val migrated = state.copy(
            uuid = generateUUID(),
            analyticsSchemaVersion = CURRENT_SCHEMA_VERSION,
        )
        saveState(migrated)
        return migrated
    }

    private fun createDefaultState(): AnalyticsState {
        return AnalyticsState(
          uuid = generateUUID(),
          enabled = false,
          analyticsSchemaVersion = CURRENT_SCHEMA_VERSION,
          lastUploadedTime = null,
        )
    }

    private fun generateUUID(): String {
        // The "ci:" prefix keeps CI runs groupable by provider while using a distinct_id
        // namespace that has no pre-existing person profile: the legacy bare slugs ("github",
        // "circleci", ...) each have a person profile from years of full-mode events, which
        // would force-upgrade anonymous events back to identified billing.
        return CiUtils.getCiProvider()?.let { "ci:$it" } ?: UUID.randomUUID().toString()
    }

    /**
     * Clear identifying user/org info from local state on logout, keeping uuid/enabled intact
     * so anonymous tracking continues under the same distinct id.
     */
    fun clearUserState(): AnalyticsState {
        val clearedState = getState().copy(
            cachedToken = null,
            user_id = null,
            email = null,
            name = null,
            workOSOrgId = null,
            orgId = null,
            orgName = null,
            orgPlan = null,
            orgTrialExpiresOn = null,
            orgStatus = null,
            currentPlan = null,
            isInTrial = null,
            daysUntilTrialExpiry = null,
            daysUntilGracePeriodExpiry = null,
        )
        saveState(clearedState)
        return clearedState
    }
}

/**
 * Extension function to add organization status to AnalyticsState
 */
fun AnalyticsState.addOrgStatusProperties(org: OrgResponse?): AnalyticsState {
    if (org == null) return this

    val orgStatus = getOrgStatus(org)
    val pricingPlan = org.metadata?.get("pricing_plan")

    // Trial status requires checking both plan type and org status
    val isInTrial = pricingPlan == "BASIC" && orgStatus == OrgStatus.ACTIVE && org.metadata["trial_expires_on"] != null

    return this.copy(
      orgStatus = orgStatus,
      currentPlan = pricingPlan?.let { OrgPlans.valueOf(it) },
      isInTrial = isInTrial,
      daysUntilTrialExpiry = if (isInTrial) calculateDaysUntil(org.metadata["trial_expires_on"]) else null,
      daysUntilGracePeriodExpiry = if (orgStatus == OrgStatus.IN_GRACE_PERIOD) calculateDaysUntil(org.metadata?.get("subscription_grace_period")) else null
    )
}

/**
 * Helper function to get organization status
 */
private fun getOrgStatus(org: OrgResponse?): OrgStatus? {
    if (org == null) return null
    if (org.metadata == null) return null

    val trialExpirationDate = org.metadata.get("trial_expires_on")
    val pricingPlan = org.metadata.get("pricing_plan")
    val gracePeriod = org.metadata.get("subscription_grace_period")

    if (gracePeriod != null) {
        val graceDate = parseDate(gracePeriod)
        if (graceDate != null) {
            val now = LocalDate.now()
            // If grace period is in the past, expired
            return if (graceDate.isBefore(now)) {
              OrgStatus.GRACE_PERIOD_EXPIRED
            } else {
              OrgStatus.IN_GRACE_PERIOD
            }
        } else {
            // If we can't parse the date, assume it's active
            return OrgStatus.IN_GRACE_PERIOD
        }
    }

    if (pricingPlan == "BASIC" && trialExpirationDate != null) {
        val trialDate = parseDate(trialExpirationDate)
        if (trialDate != null) {
            val now = LocalDate.now()
            if (trialDate.isBefore(now)) {
                return OrgStatus.TRIAL_EXPIRED
            } else {
                return OrgStatus.ACTIVE
            }
        } else {
            // If we can't parse the date, assume trial is active
            return OrgStatus.ACTIVE
        }
    }

    if (pricingPlan == "BASIC") {
        return OrgStatus.TRIAL_NOT_ACTIVE
    }

    if (listOf("CLOUD_MANUAL", "ENTERPRISE", "CLOUD").contains(pricingPlan)) {
        return OrgStatus.ACTIVE
    }

    return OrgStatus.TRIAL_NOT_ACTIVE
}

/**
 * Helper function to parse dates in multiple formats
 */
private fun parseDate(dateString: String?): LocalDate? {
    if (dateString == null) return null
    
    val formatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE, // "2030-02-19"
        DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH), // "Sep 1 2025"
        DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH), // "Sep 01 2025" (with zero-padded day)
        DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH), // "September 1 2025"
        DateTimeFormatter.ofPattern("MMMM dd yyyy", Locale.ENGLISH), // "September 01 2025" (with zero-padded day)
        DateTimeFormatter.ofPattern("MM/dd/yyyy"), // "02/19/2030"
        DateTimeFormatter.ofPattern("dd/MM/yyyy"), // "19/02/2030"
        DateTimeFormatter.ofPattern("yyyy-MM-dd"), // "2030-02-19"
    )
    
    for (formatter in formatters) {
        try {
            return LocalDate.parse(dateString, formatter)
        } catch (e: DateTimeParseException) {
            // Try next formatter
        }
    }
    return null
}

/**
 * Helper function to calculate days until a date
 */
private fun calculateDaysUntil(dateString: String?): Int? {
    if (dateString == null) return null
    val targetDate = parseDate(dateString) ?: return null
    val now = LocalDate.now()
    return java.time.temporal.ChronoUnit.DAYS.between(now, targetDate).toInt()
}
