package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.ResolvedChannel
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced
import dev.mobile.devicecore.prototype.api.Rect
import dev.mobile.devicecore.prototype.api.EvidenceSource
import org.junit.jupiter.api.Test

class DeviceCoreClasspathTest {
    @Test
    fun `device-core api types are on the compile classpath`() {
        val ua = Signal(false, EvidenceSource.UNAVAILABLE)
        val evidence = ElementEvidence(
            target = "Login",
            resolution = Resolution.Resolved(ResolvedChannel.TEXT),
            actionability = Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
            bounds = Sourced(Rect(x = 122, y = 160, width = 148, height = 26), EvidenceSource.MEASURED),
        )
        assertThat(evidence.resolution).isInstanceOf(Resolution.Resolved::class.java)
        assertThat(evidence.bounds.value?.width).isEqualTo(148)
    }
}
