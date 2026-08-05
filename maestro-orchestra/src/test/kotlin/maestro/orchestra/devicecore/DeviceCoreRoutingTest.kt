package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.Match
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test

class DeviceCoreRoutingTest {

    @Test fun `plain visible text selector routes as EXACT VISIBLE`() {
        val q = DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Welcome home")))
        assertThat(q).isEqualTo(RoutedQuery("Welcome home", Match.EXACT, null, AssertMode.VISIBLE))
    }

    @Test fun `plain notVisible text selector routes as NOT_VISIBLE`() {
        val q = DeviceCoreRouting.route(Condition(notVisible = ElementSelector(textRegex = "Spinner")))
        assertThat(q?.mode).isEqualTo(AssertMode.NOT_VISIBLE)
    }

    @Test fun `text selector with index maps to nth`() {
        val q = DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Row", index = "2")))
        assertThat(q?.index).isEqualTo(2)
    }

    @Test fun `id selector does NOT route (getById unimplemented on iOS)`() {
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(idRegex = "login_btn")))).isNull()
    }

    @Test fun `text selector with regex metacharacters does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "Item .*")))).isNull()
    }

    @Test fun `relational or trait constraints do NOT route`() {
        val below = ElementSelector(textRegex = "A", below = ElementSelector(textRegex = "B"))
        assertThat(DeviceCoreRouting.route(Condition(visible = below))).isNull()
    }

    @Test fun `condition with both visible and notVisible does NOT route`() {
        val c = Condition(visible = ElementSelector(textRegex = "A"), notVisible = ElementSelector(textRegex = "B"))
        assertThat(DeviceCoreRouting.route(c)).isNull()
    }

    @Test fun `condition with a platform guard or scriptCondition does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition(scriptCondition = "x"))).isNull()
        assertThat(DeviceCoreRouting.route(Condition(visible = ElementSelector(textRegex = "A"),
            platform = maestro.device.Platform.IOS))).isNull()
    }

    @Test fun `empty condition does NOT route`() {
        assertThat(DeviceCoreRouting.route(Condition())).isNull()
    }
}
