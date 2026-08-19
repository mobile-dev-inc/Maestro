package maestro.cli.mcp

import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.devicecore.DeviceGateway
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.RealDeviceGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * W4 (discharges the W1.6 carry-forward): the MCP session must provision a REAL connected
 * device-core driver — not the inert default Orchestra falls back to — so `run` executes on the
 * converged Orchestra and `inspect_screen` / `take_screenshot` route their device reads to the seam.
 * The `chromium` web id is the one device-free path (no adb/simctl lookup), so it's what these tests
 * exercise.
 */
class McpMaestroSessionManagerTest {

    @Test
    fun `session provisions and connects a device-core driver`() {
        val driver = mockk<DeviceGateway>(relaxed = true)
        val target = slot<DeviceCoreTarget>()
        every { driver.connect(capture(target), any()) } just Runs

        val manager = McpMaestroSessionManager(driverFactory = { driver })
        val session = manager.withSession("chromium") { it }

        assertThat(session.driver).isSameInstanceAs(driver)
        assertThat(session.platform).isEqualTo("web")
        verify { driver.connect(any(), null) }
        assertThat(target.captured.platform).isEqualTo(Platform.WEB)
        assertThat(target.captured.serial).isNull()
    }

    @Test
    fun `device read routes to the seam and surfaces a clean NotImplemented`() {
        val driver = mockk<DeviceGateway>(relaxed = true)
        every { driver.hierarchy() } throws
            MaestroException.NotImplemented("device-core driver does not yet implement hierarchy")

        val manager = McpMaestroSessionManager(driverFactory = { driver })

        // The tools call `session.driver.hierarchy()` / `.takeScreenshot(...)`; both are roadmap verbs
        // that throw NotImplemented until device-core ships them — a clean throw, not a crash.
        assertThrows<MaestroException.NotImplemented> {
            manager.withSession("chromium") { it.driver.hierarchy() }
        }
    }

    @Test
    fun `real web target has no device-core provider and throws NotImplemented at connect`() {
        // With the real driver factory, connecting a web target throws at the seam (device-core has
        // no web provider) — the intended coverage signal, surfaced to the MCP caller.
        val manager = McpMaestroSessionManager(driverFactory = { RealDeviceGateway() })

        assertThrows<MaestroException.NotImplemented> {
            manager.withSession("chromium") { }
        }
    }
}
