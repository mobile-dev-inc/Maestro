package maestro.device

/**
 * Hinge postures of a foldable device, each pinned to the hinge angle that produces it
 * (0° = closed, 180° = flat).
 *
 * [yamlValue] is the word written in YAML. It is deliberately NOT a `@JsonProperty` on each constant:
 * the constant name is what the MaestroCommand wire writes and reads back.
 */
enum class FoldPosture(val yamlValue: String, val hingeAngle: Double) {
    CLOSED("closed", 0.0),
    HALF_FOLD("halfFold", 90.0),
    FLAT("flat", 180.0);

    companion object {
        fun getByYamlValue(value: String): FoldPosture? = entries.firstOrNull { it.yamlValue == value }
    }
}
