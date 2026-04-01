package maestro.cli.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceOrientation
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.junit.jupiter.api.Test

class ApiClientParseUploadResponseTest {

    private val mapper = jacksonObjectMapper()

    // Real JSON format sent by the server (from RunMaestroRoute.kt DeviceConfigurationResponse)
    private val androidResponseJson = """
        {
          "orgId": "org-123",
          "uploadId": "upload-456",
          "appId": "app-789",
          "appBinaryId": "binary-abc",
          "deviceConfiguration": {
            "platform": "ANDROID",
            "deviceLocale": "en_US",
            "deviceName": "pixel_6",
            "deviceOs": "android-33",
            "osVersion": "33",
            "orientation": "PORTRAIT",
            "displayInfo": "Maestro_ANDROID_pixel_6_android-33"
          }
        }
    """.trimIndent()

    private val iosResponseJson = """
        {
          "orgId": "org-123",
          "uploadId": "upload-456",
          "appId": "app-789",
          "appBinaryId": "binary-abc",
          "deviceConfiguration": {
            "platform": "IOS",
            "deviceLocale": "en_US",
            "deviceName": "iPhone-11",
            "deviceOs": "iOS-18-2",
            "osVersion": "18",
            "orientation": "PORTRAIT",
            "displayInfo": "Maestro_IOS_iPhone-11_18"
          }
        }
    """.trimIndent()

    private val webResponseJson = """
        {
          "orgId": "org-123",
          "uploadId": "upload-456",
          "appId": "app-789",
          "appBinaryId": "binary-abc",
          "deviceConfiguration": {
            "platform": "WEB",
            "deviceLocale": "en_US",
            "deviceName": "chromium",
            "deviceOs": "default",
            "osVersion": "0",
            "displayInfo": "Maestro_WEB_chromium_0"
          }
        }
    """.trimIndent()

    @Test
    fun `parseUploadResponse - android response creates Android DeviceSpec`() {
        val result = parse(androidResponseJson)

        assertThat(result.orgId).isEqualTo("org-123")
        assertThat(result.uploadId).isEqualTo("upload-456")
        val spec = result.deviceSpec as DeviceSpec.Android
        assertThat(spec.platform).isEqualTo(Platform.ANDROID)
        assertThat(spec.model).isEqualTo("pixel_6")
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Test
    fun `parseUploadResponse - ios response creates Ios DeviceSpec`() {
        val result = parse(iosResponseJson)

        val spec = result.deviceSpec as DeviceSpec.Ios
        assertThat(spec.platform).isEqualTo(Platform.IOS)
        assertThat(spec.model).isEqualTo("iPhone-11")
        assertThat(spec.os).isEqualTo("iOS-18-2")
        assertThat(spec.orientation).isEqualTo(DeviceOrientation.PORTRAIT)
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Test
    fun `parseUploadResponse - web response with null orientation creates Web DeviceSpec`() {
        val result = parse(webResponseJson)

        val spec = result.deviceSpec as DeviceSpec.Web
        assertThat(spec.platform).isEqualTo(Platform.WEB)
        assertThat(spec.model).isEqualTo("chromium")
        assertThat(spec.os).isEqualTo("default")
        assertThat(spec.locale.code).isEqualTo("en_US")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): UploadResponse =
        parseUploadResponse(mapper.readValue(json, Map::class.java) as Map<*, *>)
}