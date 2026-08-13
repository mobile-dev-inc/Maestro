package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.TargetId
import org.junit.jupiter.api.Test

class DeviceCoreClasspathTest {
    @Test
    fun `device-core api types resolve on the classpath`() {
        val text = Selector.Text("hello", Match.PATTERN, ignoreCase = true)
        assertThat(text.value).isEqualTo("hello")
        assertThat(text.match).isEqualTo(Match.PATTERN)
        assertThat(TargetId.ANDROID_EMU.id).isEqualTo("android-emu")
    }
}
