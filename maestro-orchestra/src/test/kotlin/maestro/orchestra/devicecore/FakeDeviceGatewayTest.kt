package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.ElementSelector
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeDeviceGatewayTest {
    @Test
    fun `records taps and launches through the built verbs`() {
        val gw = FakeDeviceGateway()
        gw.launchApp("com.example.app")
        gw.tap(ElementSelector(idRegex = "fab"))
        assertThat(gw.launched).containsExactly("com.example.app")
        assertThat(gw.tapped).hasSize(1)
    }

    @Test
    fun `takeScreenshot writes the canned bytes into the sink`() {
        val gw = FakeDeviceGateway(screenshotBytes = byteArrayOf(9, 8, 7))
        val buffer = Buffer()
        gw.takeScreenshot(buffer, compressed = false)
        assertThat(buffer.readByteArray()).isEqualTo(byteArrayOf(9, 8, 7))
    }

    @Test
    fun `inherits the NotImplemented default for an unbuilt verb`() {
        assertThrows<MaestroException.NotImplemented> { FakeDeviceGateway().inputText("x") }
    }
}
