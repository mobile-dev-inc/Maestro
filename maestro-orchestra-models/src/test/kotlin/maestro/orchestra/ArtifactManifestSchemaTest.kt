package maestro.orchestra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The schema is hand-written, so it can drift from the enums it documents.
 * These tests are the guard: a new [ArtifactKind] / [ArtifactFormat] that
 * isn't documented in the schema fails the build, forcing the docs to keep up.
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
}
