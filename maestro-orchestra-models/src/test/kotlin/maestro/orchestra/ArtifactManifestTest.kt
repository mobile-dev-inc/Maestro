package maestro.orchestra

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ArtifactManifestTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `round-trips single and collection entries`() {
        val manifest = ArtifactManifest(
            entries = listOf(
                ArtifactEntry(
                    kind = ArtifactKind.SCREEN_RECORDING,
                    format = ArtifactFormat.MP4,
                    relativePath = "screen_recording.mp4",
                    sizeBytes = 12_345_678,
                ),
                ArtifactEntry(
                    kind = ArtifactKind.SCREENSHOT,
                    format = ArtifactFormat.PNG,
                    relativePath = "screenshots/",
                    count = 42,
                ),
            ),
        )

        val json = mapper.writeValueAsString(manifest)
        val decoded = mapper.readValue<ArtifactManifest>(json)

        assertThat(decoded).isEqualTo(manifest)
    }

    @Test
    fun `enums serialize as SCREAMING_SNAKE_CASE on the wire`() {
        val json = mapper.writeValueAsString(
            ArtifactEntry(ArtifactKind.SCREEN_RECORDING, ArtifactFormat.MP4, "screen_recording.mp4"),
        )

        assertThat(json).contains("\"SCREEN_RECORDING\"")
        assertThat(json).contains("\"MP4\"")
    }

    @Test
    fun `tolerates unknown fields for forward compatibility`() {
        val tolerant = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val json = """{"schemaVersion":1,"entries":[],"futureField":"ignored"}"""

        val decoded = tolerant.readValue<ArtifactManifest>(json)

        assertThat(decoded.entries).isEmpty()
    }

    @Test
    fun `defaults schemaVersion and empty entries`() {
        val decoded = mapper.readValue<ArtifactManifest>("{}")

        assertThat(decoded.schemaVersion).isEqualTo(1)
        assertThat(decoded.entries).isEmpty()
    }
}
