package maestro.device.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.truth.Truth.assertThat
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.locale.AndroidLocale
import org.junit.jupiter.api.Test

class DeviceSpecSerializationTest {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(DeviceSpecModule())

    // --- Round-trip tests ---

    @Test
    fun `round-trip Android DeviceSpec with only required fields`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-33")
        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)
        assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `round-trip iOS DeviceSpec with only required fields`() {
        val spec = DeviceSpec.Ios(model = "iPhone-11", os = "iOS-17-5")
        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)
        assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `round-trip Web DeviceSpec with only required fields`() {
        val spec = DeviceSpec.Web(model = "chromium", os = "default")
        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)
        assertThat(deserialized).isEqualTo(spec)
    }

    @Test
    fun `round-trip Android DeviceSpec with all fields overridden`() {
        val spec = DeviceSpec.Android(
            model = "pixel_xl",
            os = "android-34",
            locale = AndroidLocale.fromString("de_DE"),
            cpuArchitecture = CPU_ARCHITECTURE.X86_64,
        )
        val json = mapper.writeValueAsString(spec)
        val deserialized = mapper.readValue(json, DeviceSpec::class.java)
        assertThat(deserialized).isEqualTo(spec)
    }

    // --- Sparse-serialization tests (only required fields + differing values) ---

    @Test
    fun `Android with only required fields serializes sparsely`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-33")
        val json = mapper.readTree(mapper.writeValueAsString(spec))

        assertThat(json.fieldNames().asSequence().toSet()).containsExactly(
            "platform", "model", "os"
        )
        assertThat(json.get("platform").asText()).isEqualTo("ANDROID")
        assertThat(json.get("model").asText()).isEqualTo("pixel_6")
        assertThat(json.get("os").asText()).isEqualTo("android-33")
    }

    @Test
    fun `Android with differing locale includes locale as object`() {
        val spec = DeviceSpec.Android(
            model = "pixel_6",
            os = "android-33",
            locale = AndroidLocale.fromString("de_DE"),
        )
        val json = mapper.readTree(mapper.writeValueAsString(spec))

        assertThat(json.fieldNames().asSequence().toSet()).containsExactly(
            "platform", "model", "os", "locale"
        )
        assertThat(json.get("locale").get("code").asText()).isEqualTo("de_DE")
        assertThat(json.get("locale").get("platform").asText()).isEqualTo("ANDROID")
    }

    @Test
    fun `Android with non-default cpuArchitecture includes that field`() {
        val spec = DeviceSpec.Android(
            model = "pixel_6",
            os = "android-33",
            cpuArchitecture = CPU_ARCHITECTURE.X86_64,
        )
        val json = mapper.readTree(mapper.writeValueAsString(spec))
        assertThat(json.fieldNames().asSequence().toSet()).containsExactly(
            "platform", "model", "os", "cpuArchitecture"
        )
        assertThat(json.get("cpuArchitecture").asText()).isEqualTo("X86_64")
    }

    @Test
    fun `iOS with only required fields serializes sparsely`() {
        val spec = DeviceSpec.Ios(model = "iPhone-11", os = "iOS-17-5")
        val json = mapper.readTree(mapper.writeValueAsString(spec))

        assertThat(json.fieldNames().asSequence().toSet()).containsExactly(
            "platform", "model", "os"
        )
    }

    @Test
    fun `Web with only required fields serializes sparsely`() {
        val spec = DeviceSpec.Web(model = "chromium", os = "default")
        val json = mapper.readTree(mapper.writeValueAsString(spec))

        assertThat(json.fieldNames().asSequence().toSet()).containsExactly(
            "platform", "model", "os"
        )
    }

    // --- Computed fields must never appear in output ---

    @Test
    fun `computed fields are never serialized`() {
        val spec = DeviceSpec.Android(model = "pixel_6", os = "android-33")
        val json = mapper.readTree(mapper.writeValueAsString(spec))

        assertThat(json.has("osVersion")).isFalse()
        assertThat(json.has("deviceName")).isFalse()
        assertThat(json.has("tag")).isFalse()
        assertThat(json.has("emulatorImage")).isFalse()
    }

    // --- Legacy verbose JSON still deserializes (DB backward compat) ---

    @Test
    fun `legacy verbose Android JSON with removed fields deserializes`() {
        val legacyJson = """
            {
              "platform": "ANDROID",
              "model": "pixel_6",
              "os": "android-33",
              "locale": {"code": "en_US", "platform": "ANDROID"},
              "orientation": "PORTRAIT",
              "disableAnimations": false,
              "cpuArchitecture": "ARM64",
              "osVersion": 33,
              "deviceName": "Maestro_ANDROID_pixel_6_android-33",
              "tag": "google_apis",
              "emulatorImage": "system-images;android-33;google_apis;arm64-v8a"
            }
        """.trimIndent()

        val lenientMapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(DeviceSpecModule())
            .configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false,
            )

        val spec = lenientMapper.readValue(legacyJson, DeviceSpec::class.java) as DeviceSpec.Android
        assertThat(spec.model).isEqualTo("pixel_6")
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.osVersion).isEqualTo(33)  // computed, not from JSON
    }

    @Test
    fun `legacy verbose iOS JSON with removed fields deserializes`() {
        val legacyJson = """
            {
              "platform": "IOS",
              "model": "iPhone-11",
              "os": "iOS-17-5",
              "locale": {"code": "en_GB", "platform": "IOS"},
              "orientation": "PORTRAIT",
              "disableAnimations": false,
              "snapshotKeyHonorModalViews": true,
              "osVersion": 17,
              "deviceName": "Maestro_IOS_iPhone-11_17"
            }
        """.trimIndent()

        val lenientMapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(DeviceSpecModule())
            .configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false,
            )

        val spec = lenientMapper.readValue(legacyJson, DeviceSpec::class.java) as DeviceSpec.Ios
        assertThat(spec.model).isEqualTo("iPhone-11")
        assertThat(spec.os).isEqualTo("iOS-17-5")
        assertThat(spec.locale.code).isEqualTo("en_GB")
    }
}
