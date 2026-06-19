package maestro.conformance.report

import maestro.conformance.behavior.OracleKind

data class CommandRecord(
    val command: String,
    val coverage: String,
    val args: Map<String, Any?>,
    val oracleKind: OracleKind,
    val expected: Map<String, Any?>,
    val actual: Map<String, Any?>,
    val verdict: Boolean,
    val failureReason: String?,
    val actMs: Long,
    val totalMs: Long,
    val artifacts: List<String> = emptyList(),
)
