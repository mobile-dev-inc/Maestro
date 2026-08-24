package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.SystemImageTag
import org.junit.jupiter.api.Test

class PickDeviceViewTest {

    @Test
    fun `no tag requested keeps the default Android spec untouched`() {
        val spec = PickDeviceView.requestDeviceOptions(Platform.ANDROID)

        assertThat(spec).isEqualTo(DeviceSpec.Android.DEFAULT)
        assertThat((spec as DeviceSpec.Android).tag).isEqualTo(SystemImageTag.GOOGLE_APIS)
    }

    @Test
    fun `a requested tag overrides the default Android spec tag`() {
        val spec = PickDeviceView.requestDeviceOptions(
            platform = Platform.ANDROID,
            androidSystemImage = SystemImageTag.GOOGLE_APIS_PLAYSTORE,
        )

        val android = spec as DeviceSpec.Android
        assertThat(android.tag).isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
        // Everything else must still match the default — the tag is the only override.
        assertThat(android).isEqualTo(
            DeviceSpec.Android.DEFAULT.copy(tag = SystemImageTag.GOOGLE_APIS_PLAYSTORE)
        )
    }

    @Test
    fun `an Android tag is ignored on iOS`() {
        val spec = PickDeviceView.requestDeviceOptions(
            platform = Platform.IOS,
            androidSystemImage = SystemImageTag.GOOGLE_APIS_PLAYSTORE,
        )

        assertThat(spec).isEqualTo(DeviceSpec.Ios.DEFAULT)
    }

    @Test
    fun `an Android tag is ignored on web`() {
        val spec = PickDeviceView.requestDeviceOptions(
            platform = Platform.WEB,
            androidSystemImage = SystemImageTag.GOOGLE_APIS_PLAYSTORE,
        )

        assertThat(spec).isEqualTo(DeviceSpec.Web.DEFAULT)
    }
}
