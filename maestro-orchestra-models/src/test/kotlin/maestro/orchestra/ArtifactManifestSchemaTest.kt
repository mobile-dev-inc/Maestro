package maestro.orchestra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * The schema is hand-written, so it can drift from the model it documents.
 * These tests are the guard: a new [ArtifactKind] / [ArtifactFormat], or a new
 * field on [ArtifactEntry] / [ArtifactManifest], that isn't documented in the
 * current schema ([ArtifactManifest.SCHEMA_RESOURCE]) fails the build. The
 * schema sets `additionalProperties: true` so additive fields stay
 * forward-compatible for validators; the field check keeps the schema a
 * complete, drift-free reference regardless. Superseded versions are frozen
 * once published (enum adds are not additive), guarded by checksum.
 */
class ArtifactManifestSchemaTest {

    private val schema: JsonNode = jacksonObjectMapper().readTree(
        javaClass.getResourceAsStream(ArtifactManifest.SCHEMA_RESOURCE)
            ?: error("schema resource not found at ${ArtifactManifest.SCHEMA_RESOURCE}"),
    )

    @Test
    fun `every ArtifactKind is documented with a description`() {
        val documented = schema.at("/\$defs/ArtifactKind/oneOf").associate { node ->
            node["const"].asText() to node["description"]?.asText().orEmpty()
        }

        assertThat(documented.keys).containsExactlyElementsIn(ArtifactKind.entries.map { it.name })
        val undocumented = documented.filterValues { it.isBlank() }.keys
        assertThat(undocumented).isEmpty()
    }

    @Test
    fun `every ArtifactFormat is documented in the format enum`() {
        val documented = schema.at("/\$defs/ArtifactFormat/enum").map { it.asText() }

        assertThat(documented).containsExactlyElementsIn(ArtifactFormat.entries.map { it.name })
    }

    @Test
    fun `published v1 schema stays frozen`() {
        // v1 is published and may be cached by external validators; it must never change.
        // Enum values added since (SCREENSHOT_DIFF) live in v2 onward. If this fails, revert
        // the v1 edit and put the change in a new schema version instead.
        val v1 = javaClass.getResourceAsStream("/maestro/orchestra/artifact-manifest/v1.schema.json")
            ?.readBytes() ?: error("v1 schema resource not found")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(v1)
            .joinToString("") { "%02x".format(it) }

        assertThat(sha256).isEqualTo("dd5bac62f9f4a5313761a205342f3d22033554c0a9f57a208d6a7d28212d18b2")
    }

    @Test
    fun `every ArtifactManifest field is documented in the schema`() {
        assertEveryFieldDocumented(ArtifactManifest::class, "/properties")
    }

    @Test
    fun `every ArtifactEntry field is documented in the schema`() {
        assertEveryFieldDocumented(ArtifactEntry::class, "/\$defs/ArtifactEntry/properties")
    }

    /**
     * Asserts the schema documents every constructor field of [type]. The schema
     * may carry extra wire-only properties (e.g. `$schema`) the model doesn't.
     */
    private fun assertEveryFieldDocumented(type: KClass<*>, propertiesPointer: String) {
        val modelFields = type.primaryConstructor!!.parameters.mapNotNull { it.name }
        val documented = schema.at(propertiesPointer).fieldNames().asSequence().toSet()
        assertThat(documented).containsAtLeastElementsIn(modelFields)
    }
}
