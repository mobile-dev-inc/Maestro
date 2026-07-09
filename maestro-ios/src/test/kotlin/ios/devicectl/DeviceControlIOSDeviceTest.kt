package ios.devicectl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeviceControlIOSDeviceTest {

    @Test
    fun `launch command without arguments has no terminator`() {
        assertEquals(
            listOf(
                "xcrun", "devicectl", "device", "process", "launch",
                "--json-output", "/tmp/out.json",
                "--device", "UDID-1",
                "com.example.app",
            ),
            DeviceControlIOSDevice.buildLaunchCommand("UDID-1", "com.example.app", emptyMap(), "/tmp/out.json"),
        )
    }

    @Test
    fun `launch command passes app arguments after -- terminator in iOS form`() {
        assertEquals(
            listOf(
                "xcrun", "devicectl", "device", "process", "launch",
                "--json-output", "/tmp/out.json",
                "--device", "UDID-1",
                "--",
                "com.example.app",
                "-foo", "bar",
                "isE2E", "true",
            ),
            DeviceControlIOSDevice.buildLaunchCommand(
                "UDID-1",
                "com.example.app",
                linkedMapOf("foo" to "bar", "isE2E" to true),
                "/tmp/out.json",
            ),
        )
    }

    @Test
    fun `terminate command targets pid`() {
        assertEquals(
            listOf(
                "xcrun", "devicectl", "device", "process", "terminate",
                "--device", "UDID-1",
                "--pid", "4242",
            ),
            DeviceControlIOSDevice.buildTerminateCommand("UDID-1", 4242L),
        )
    }

    @Test
    fun `pid is parsed from devicectl launch json output`() {
        val json = """
            {"info":{"outcome":"success"},"result":{"process":{"processIdentifier":1234,"executable":"file:///private/var/containers/x/App.app/App"}}}
        """.trimIndent()
        assertEquals(1234L, DeviceControlIOSDevice.parseLaunchedProcessId(json))
    }

    @Test
    fun `pid parse returns null on malformed or missing output`() {
        assertNull(DeviceControlIOSDevice.parseLaunchedProcessId("not json"))
        assertNull(DeviceControlIOSDevice.parseLaunchedProcessId("""{"result":{}}"""))
    }

    @Test
    fun `clearAppState and addMedia fail loudly instead of silently no-oping`() {
        val device = DeviceControlIOSDevice("UDID-1")
        assertThrows(UnsupportedOperationException::class.java) { device.clearAppState("com.example.app") }
        assertThrows(UnsupportedOperationException::class.java) { device.addMedia("/tmp/photo.png") }
    }
}
