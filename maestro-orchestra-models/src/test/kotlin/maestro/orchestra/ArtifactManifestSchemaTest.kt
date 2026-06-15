package maestro.orchestra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * The schema is hand-written, so it can drift from the model it documents.
 * These tests are the guard: a new [ArtifactKind] / [ArtifactFormat], or a new
 * field on [ArtifactEntry] / [ArtifactManifest], that isn't documented in the
 * schema fails the build. The field check matters because the schema sets
 * `additionalProperties: false`, so an undocumented field would be rejected by
 * any consumer validating a manifest against it.
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
