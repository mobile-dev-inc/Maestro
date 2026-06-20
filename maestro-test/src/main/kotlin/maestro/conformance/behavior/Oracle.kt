package maestro.conformance.behavior

enum class Coverage { FRAMEWORK_SENSITIVE, MIXED, DEVICE_LEVEL }
enum class OracleKind { APP_EVENT, RETURN_VALUE, DEVICE_PROBE }

data class Verdict(val pass: Boolean, val reason: String?) {
    companion object {
        fun pass() = Verdict(true, null)
        fun fail(reason: String) = Verdict(false, reason)
    }
}

data class CommandOutcome(
    val verdict: Verdict,
    val oracleKind: OracleKind,
    val expected: Map<String, Any?>,
    val actual: Map<String, Any?>,
    val args: Map<String, Any?>,
)
