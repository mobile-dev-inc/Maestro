package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.AXElement
import hierarchy.AXFrame
import hierarchy.ViewHierarchy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ios.IOSDeviceErrors
import maestro.utils.network.XCUITestServerError
import maestro.CLIPS_CHILDREN_ATTRIBUTE
import maestro.DeviceUnreachableException
import maestro.MaestroException
import org.junit.jupiter.api.Test
import java.io.File
import xcuitest.crash.IOSCrashFileFinder
import xcuitest.crash.IOSAppTerminationFinder
import xcuitest.crash.AppTermination
import org.junit.jupiter.api.io.TempDir
import maestro.device.AppCrashReport
import org.junit.jupiter.api.assertThrows
import xcuitest.api.DeviceInfo
import java.net.SocketTimeoutException
import java.time.Instant
import okio.Buffer

class IOSDriverTest {

    @Test
    fun `startScreenRecording forwards the start time the device reports`() {
        val startedAt = Instant.ofEpochMilli(1_700_000_000_000L)
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.startScreenRecording(any()) } returns object : IOSScreenRecording {
            override val startedAt: Instant = startedAt
            override fun close() {}
        }

        val recording = IOSDriver(iosDevice).startScreenRecording(Buffer())

        assertThat(recording.startedAt).isEqualTo(startedAt)
    }

    @Test
    fun `IOSDeviceErrors Unreachable from the device is translated to DeviceUnreachableException`() {
        val cause = SocketTimeoutException("Read timed out")
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", cause)

        val driver = IOSDriver(iosDevice)

        val thrown = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(thrown.operation).isEqualTo("deviceInfo")
        assertThat(thrown.cause).isInstanceOf(IOSDeviceErrors.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `IOSDriver does not cache - subsequent calls invoke the device again`() {
        // Fail-fast for the dead-runner case lives at the transport layer (XCTestDriverClient).
        // IOSDriver is now a thin translator: it converts each IOSDeviceErrors.Unreachable into
        // a DeviceUnreachableException without short-circuiting on its own. When the underlying
        // device keeps throwing (mimicking a still-tripped transport latch), the driver translates
        // each call independently.
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", SocketTimeoutException())

        val driver = IOSDriver(iosDevice)

        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `XCTest finding the app gone is an app-not-running failure, not a crash`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws XCUITestServerError.AppNotRunning("Application com.example.app is not running")

        val driver = IOSDriver(iosDevice)

        val error = assertThrows<MaestroException.AppNotRunning> { driver.deviceInfo() }
        assertThat(error.message).isEqualTo("The app is not running. It may have crashed or been closed.")
        assertThrows<MaestroException.AppNotRunning> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `successful calls pass through unchanged`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } returns DeviceInfo(
            widthPixels = 1170,
            heightPixels = 2532,
            widthPoints = 390,
            heightPoints = 844,
        )

        val driver = IOSDriver(iosDevice)

        driver.deviceInfo()
        driver.deviceInfo()
        driver.deviceInfo()

        verify(exactly = 3) { iosDevice.deviceInfo() }
    }

    @Test
    fun `scroll views, tables and collection views are marked as clipping their content when enabled`() {
        val root = IOSDriver(iosDeviceWithScrollingElements(), clipToScrollContainers = true)
            .contentDescriptor(excludeKeyboardElements = false)

        val clipping = root.aggregate()
            .filter { it.attributes[CLIPS_CHILDREN_ATTRIBUTE] == "true" }
            .map { it.attributes["resource-id"] }
        assertThat(clipping).containsExactly("scroll-view", "table", "collection-view")
    }

    @Test
    fun `nothing is marked as clipping by default`() {
        val root = IOSDriver(iosDeviceWithScrollingElements())
            .contentDescriptor(excludeKeyboardElements = false)

        assertThat(root.aggregate().filter { it.attributes.containsKey(CLIPS_CHILDREN_ATTRIBUTE) }).isEmpty()
    }

    private fun iosDeviceWithScrollingElements(): IOSDevice {
        fun element(elementType: Int, identifier: String, children: List<AXElement> = emptyList()) = AXElement(
            label = "",
            elementType = elementType,
            identifier = identifier,
            horizontalSizeClass = 0,
            windowContextID = 0,
            verticalSizeClass = 0,
            selected = false,
            displayID = 0,
            hasFocus = false,
            placeholderValue = null,
            value = null,
            frame = AXFrame(x = 0f, y = 0f, width = 402f, height = 874f),
            enabled = true,
            title = null,
            children = ArrayList(children),
        )
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.viewHierarchy(any()) } returns ViewHierarchy(
            axElement = element(
                elementType = 2,
                identifier = "application",
                children = listOf(
                    element(elementType = 46, identifier = "scroll-view"),
                    element(elementType = 26, identifier = "table"),
                    element(elementType = 32, identifier = "collection-view"),
                    element(elementType = 1, identifier = "other"),
                    element(elementType = 75, identifier = "cell"),
                ),
            ),
            depth = 2,
        )
        return iosDevice
    }

    private fun findAppCrash(
        termination: AppTermination?,
        report: File?,
        logReadable: Boolean = true,
    ): Triple<AppCrashReport?, IOSCrashFileFinder, File?> {
        val iosDevice = mockk<IOSDevice>(relaxed = true) { every { deviceId } returns "SIM" }
        val terminations = mockk<IOSAppTerminationFinder> {
            every { find("SIM", "com.example.app", any()) } returns if (logReadable) listOfNotNull(termination) else null
        }
        val reports = mockk<IOSCrashFileFinder> {
            every { waitForCrashFileOfProcess("SIM", any(), any(), any(), any()) } returns report
            every { waitForCrashFile("SIM", "com.example.app", any(), any(), any()) } returns report
        }
        val driver = IOSDriver(iosDevice, crashFileFinder = reports, terminationFinder = terminations)
        return Triple(driver.findAppCrash("com.example.app", sinceEpochMs = 0), reports, report)
    }

    @Test
    fun `the app did not crash - no crash, and no time spent waiting for a report`() {
        val (crash, reports) = findAppCrash(termination = null, report = null)

        assertThat(crash).isNull()
        verify(exactly = 0) { reports.waitForCrashFileOfProcess(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { reports.waitForCrashFile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the app crashed and the system wrote its report - the report of that process is returned`(@TempDir dir: File) {
        val ips = File(dir, "App.ips").apply { writeText("the report") }
        val (crash, reports) = findAppCrash(AppTermination(pid = 4242, domain = 2, code = 6), ips)

        assertThat(crash).isEqualTo(AppCrashReport(message = "App crashed (SIGABRT)", content = "the report"))
        verify { reports.waitForCrashFileOfProcess("SIM", 4242, any(), IOSDriver.CRASH_REPORT_TIMEOUT_MS, any()) }
    }

    @Test
    fun `the app crashed but no report ever appears - it is still a crash`() {
        val (crash, _) = findAppCrash(AppTermination(pid = 4242, domain = 2, code = 11), report = null)

        assertThat(crash!!.message).isEqualTo("App crashed (SIGSEGV)")
        assertThat(crash.content).contains("com.example.app (pid 4242) was terminated by SIGSEGV")
    }

    @Test
    fun `the simulator log cannot be read - falls back to one look for a report, without waiting`() {
        val (crash, reports) = findAppCrash(termination = null, report = null, logReadable = false)

        assertThat(crash).isNull()
        verify(exactly = 1) { reports.waitForCrashFile("SIM", "com.example.app", any(), 0, any()) }
    }
}
