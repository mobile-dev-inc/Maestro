package maestro.cli.promotion

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.cli.util.EnvUtils
import java.nio.file.Path
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@JsonIgnoreProperties(ignoreUnknown = true)
data class PromotionState(
    val fasterResultsLastShown: String? = null,
    val debugLastShown: String? = null,
    val cloudCommandLastUsed: String? = null
)

/**
 * Manages promotion message state persistence.
 * Similar to AnalyticsStateManager, stores all promotion states in a single JSON file.
 */
class PromotionStateManager {
    private val promotionStatePath: Path = EnvUtils.xdgStateHome().resolve("promotion-state.json")
    
    private val JSON = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private var _promotionState: PromotionState? = null

    private fun getState(): PromotionState {
        if (_promotionState == null) {
            _promotionState = loadState()
        }
        return _promotionState!!
    }

    fun getLastShownDate(key: String): String? {
        return when (key) {
            "fasterResults" -> getState().fasterResultsLastShown
            "debug" -> getState().debugLastShown
            else -> null
        }
    }

    fun setLastShownDate(key: String, date: String) {
        val currentState = getState()
        val updatedState = when (key) {
            "fasterResults" -> currentState.copy(fasterResultsLastShown = date)
            "debug" -> currentState.copy(debugLastShown = date)
            else -> currentState
        }
        saveState(updatedState)
    }

    fun recordCloudCommandUsage() {
        val today = LocalDate.now().toString()
        val currentState = getState()
        saveState(currentState.copy(cloudCommandLastUsed = today))
    }

    fun wasCloudCommandUsedWithinDays(days: Int): Boolean {
        val lastUsed = getState().cloudCommandLastUsed ?: return false
        val lastUsedDate = try {
            LocalDate.parse(lastUsed)
        } catch (e: Exception) {
            return false
        }
        val today = LocalDate.now()
        val daysSince = ChronoUnit.DAYS.between(lastUsedDate, today)
        return daysSince < days
    }

    private fun saveState(state: PromotionState) {
        val stateJson = JSON.writeValueAsString(state)
        promotionStatePath.parent.toFile().mkdirs()
        promotionStatePath.writeText(stateJson + "\n")
        _promotionState = state
    }

    private fun loadState(): PromotionState {
        return try {
            if (promotionStatePath.exists()) {
                JSON.readValue(promotionStatePath.readText())
            } else {
                PromotionState()
            }
        } catch (e: Exception) {
            PromotionState()
        }
    }
}

