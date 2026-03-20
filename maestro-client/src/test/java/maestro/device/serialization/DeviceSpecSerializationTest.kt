package maestro.device.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.truth.Truth
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceOrientation
import maestro.device.DeviceSpec
import maestro.device.DeviceSpecRequest
import org.junit.jupiter.api.Test

class DeviceSpecSerializationTest {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(DeviceSpecModule())

    @Test
    fun `round-trip Android DeviceSpec`() {
        val spec = DeviceSpec.fromRequest(
          DeviceSpecRequest.Android()
        )

        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)

        Truth.assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `round-trip iOS DeviceSpec`() {
        val spec = DeviceSpec.fromRequest(
            DeviceSpecRequest.Ios()
        )

        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)

        Truth.assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `round-trip Web DeviceSpec`() {
        val spec = DeviceSpec.fromRequest(
          DeviceSpecRequest.Web()
        )

        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)

        Truth.assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `serialized JSON has expected structure`() {
        val spec = DeviceSpec.fromRequest(
            DeviceSpecRequest.Android(
                model = "pixel_6",
                os = "android-33",
                locale = "en_US",
                orientation = DeviceOrientation.PORTRAIT,
                disableAnimations = false,
                cpuArchitecture = CPU_ARCHITECTURE.ARM64,
            )
        )

        val json = mapper.readTree(mapper.writeValueAsString(spec))

        Truth.assertThat(json.get("platform").asText()).isEqualTo("ANDROID")
        Truth.assertThat(json.get("model").asText()).isEqualTo("pixel_6")
        Truth.assertThat(json.get("os").asText()).isEqualTo("android-33")
        Truth.assertThat(json.get("locale").get("code").asText()).isEqualTo("en_US")
        Truth.assertThat(json.get("locale").get("platform").asText()).isEqualTo("ANDROID")
        Truth.assertThat(json.get("orientation").asText()).isEqualTo("PORTRAIT")
        Truth.assertThat(json.get("disableAnimations").asBoolean()).isFalse()
        Truth.assertThat(json.get("cpuArchitecture").asText()).isEqualTo("ARM64")
    }
}
