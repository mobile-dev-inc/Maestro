package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.AdbStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.android.AndroidDeviceConnection
import okio.Buffer
import org.junit.jupiter.api.Test
import java.util.zip.ZipInputStream

/**
 * Unit tests for `permissions: { all: allow }`, the default `launchApp` applies. `maestro-app.apk`
 * stands in for the app under test, so the manifest flowing through is real binary XML.
 */
class AndroidDriverAllPermissionsTest {

    private val apkBytes = javaClass.getResourceAsStream("/maestro-app.apk")!!.use { it.readBytes() }

    private val manifestBytes = ZipInputStream(apkBytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.first { it.name == "AndroidManifest.xml" }
        zip.readBytes()
    }

    private val basePath = "/data/app/~~abc==/com.example.app-xyz==/base.apk"

    // What maestro-app.apk declares.
    private val declaredPermissions = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_MOCK_LOCATION",
        "android.permission.CHANGE_CONFIGURATION",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.WRITE_SETTINGS",
    )

    private fun reply(text: String): AdbShellResponse = mockk {
        every { exitCode } returns 0
        every { output } returns text
    }

    private fun stream(bytes: ByteArray): AdbStream = mockk {
        every { source } returns Buffer().write(bytes)
        every { close() } returns Unit
    }

    /** Nothing is relaxed: a call this does not script is a call the driver was not to make. */
    private fun connection(
        apkPathLine: String = "package:$basePath=com.example.app",
        unzipOutput: ByteArray = manifestBytes,
    ): AndroidDeviceConnection = mockk<AndroidDeviceConnection>().also { connection ->
        every { connection.shell(any()) } returns reply("")
        every { connection.shell(APK_PATH_COMMAND) } returns reply(apkPathLine)
        every { connection.open(any()) } returns stream(unzipOutput)
    }

    private fun allowAll(connection: AndroidDeviceConnection) {
        AndroidDriver(connection = connection).setPermissions("com.example.app", mapOf("all" to "allow"))
    }

    private fun grantedBy(connection: AndroidDeviceConnection): List<String> {
        val commands = mutableListOf<String>()
        verify { connection.shell(capture(commands)) }
        return commands.filter { it.startsWith("pm grant ") }.map { it.substringAfterLast(' ') }
    }

    @Test
    fun `grants the permissions the manifest declares`() {
        val connection = connection()

        allowAll(connection)

        assertThat(grantedBy(connection)).containsExactlyElementsIn(declaredPermissions)
    }

    @Test
    fun `lifts the manifest out of the app instead of pulling the APK`() {
        val connection = connection()

        allowAll(connection)

        verify { connection.open("exec:unzip -p $basePath AndroidManifest.xml") }
        // Both routes open by looking the APK up, so one lookup means the old one never began.
        // (`pull` itself is beyond mockk, which cannot stand in for its sealed return type.)
        verify(exactly = 1) { connection.shell(APK_PATH_COMMAND) }
    }

    @Test
    fun `falls back to pulling the APK when the device has no unzip`() {
        // `unzip` only exists from API 27 on; before that the shell complains where the manifest
        // should be, and the parser rejecting that is what takes the old route.
        val connection = connection(unzipOutput = "/system/bin/sh: unzip: not found\n".toByteArray())

        allowAll(connection)

        // The old route looks the APK up again on its way to the transfer, which needs a device.
        verify(exactly = 2) { connection.shell(APK_PATH_COMMAND) }
        assertThat(grantedBy(connection)).isEmpty()
    }

    @Test
    fun `grants nothing when the device cannot locate the app`() {
        val connection = connection(apkPathLine = "")

        allowAll(connection)

        assertThat(grantedBy(connection)).isEmpty()
    }

    @Test
    fun `an explicit permission map still bypasses the all path entirely`() {
        val connection = connection()

        AndroidDriver(connection = connection).setPermissions("com.example.app", mapOf("camera" to "deny"))

        verify { connection.shell("pm revoke com.example.app android.permission.CAMERA") }
        verify(exactly = 0) { connection.shell(APK_PATH_COMMAND) }
        verify(exactly = 0) { connection.open(any()) }
    }

    private companion object {
        const val APK_PATH_COMMAND = "pm list packages -f --user 0 | grep com.example.app | head -1"
    }
}
