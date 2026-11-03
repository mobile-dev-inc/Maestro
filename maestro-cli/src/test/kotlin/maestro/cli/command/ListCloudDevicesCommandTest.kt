package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.api.CloudDefaultDevice
import maestro.cli.api.CloudDeprecation
import maestro.cli.api.CloudDeviceModel
import maestro.cli.api.CloudDeviceOs
import maestro.cli.api.CloudPlatformDevices
import maestro.device.Platform
import org.junit.jupiter.api.Test

class ListCloudDevicesCommandTest {

    @Test
    fun `marks the default and deprecated devices, and explains the marker once`() {
        val removal = CloudDeprecation("2026-11-09")
        val devices = mapOf(
            "ios" to CloudPlatformDevices(
                default = CloudDefaultDevice("iPhone-11", "iOS-17-5"),
                devices = listOf(
                    CloudDeviceModel("iPhone-8", removal, listOf(CloudDeviceOs("iOS-16-4", removal))),
                    CloudDeviceModel("iPhone-11", null, listOf(CloudDeviceOs("iOS-16-4", removal), CloudDeviceOs("iOS-17-5", null))),
                ),
            ),
            // From a backend without /catalog: no default, nothing deprecated.
            "android" to CloudPlatformDevices(null, listOf(CloudDeviceModel("pixel_6", null, listOf(CloudDeviceOs("android-34", null))))),
        )

        val lines = ListCloudDevicesCommand()
            .renderDevices(devices, listOf(Platform.IOS, Platform.ANDROID))
            .map { it.replace(Regex("\u001B\\[[\\d;]*[^\\d;]"), "").trimEnd() }

        assertThat(lines).containsExactly(
            "iOS   default: iPhone-11 on iOS-17-5",
            "  iPhone-8    iOS-16-4*   deprecated",
            "  iPhone-11   iOS-16-4*, iOS-17-5",
            "",
            "Android",
            "  pixel_6   android-34",
            "",
            "* Deprecated, removed from Maestro Cloud on 2026-11-09: iOS-16-4",
        ).inOrder()
    }
}
