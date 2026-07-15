package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

class PickDeviceViewTest {

    private lateinit var originalIn: InputStream
    private lateinit var originalOut: PrintStream
    private lateinit var outputStream: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        originalIn = System.`in`
        originalOut = System.out
        outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
    }

    @AfterEach
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
    }

    @Test
    fun `pickRunningDevice prints a compact list and selects by displayed index`() {
        val android = connectedDevice("emulator-5554", Platform.ANDROID)
        val ios = connectedDevice("iPhone 15 Pro", Platform.IOS)
        val secondAndroid = connectedDevice("Pixel 8", Platform.ANDROID)
        setInput("2")

        val selected = PickDeviceView.pickRunningDevice(listOf(android, ios, secondAndroid))

        assertThat(selected).isEqualTo(ios)
        val expectedOutput = """
            There are multiple connected devices
            [1]: emulator-5554
            [2]: iPhone 15 Pro
            [3]: Pixel 8

            Please choose one (or "q" to quit):
            """.trimIndent() + " "

        assertThat(output()).contains(expectedOutput)
    }

    @Test
    fun `pickRunningDevice prompts again after invalid input`() {
        val device = connectedDevice("emulator-5554", Platform.ANDROID)
        setInput("invalid", "2", "1")

        val selected = PickDeviceView.pickRunningDevice(listOf(device))

        assertThat(selected).isEqualTo(device)
        assertThat(output().split(PROMPT).size - 1).isEqualTo(3)
    }

    @Test
    fun `pickRunningDevice allows quitting`() {
        setInput("Q")

        val error = assertThrows<CliError> {
            PickDeviceView.pickRunningDevice(
                listOf(connectedDevice("emulator-5554", Platform.ANDROID))
            )
        }

        assertThat(error).hasMessageThat().isEqualTo("Device selection was cancelled")
    }

    private fun connectedDevice(description: String, platform: Platform): Device.Connected {
        val deviceSpec = when (platform) {
            Platform.ANDROID -> DeviceSpec.Android.DEFAULT
            Platform.IOS -> DeviceSpec.Ios.DEFAULT
            Platform.WEB -> DeviceSpec.Web.DEFAULT
        }
        val deviceType = when (platform) {
            Platform.ANDROID -> Device.DeviceType.EMULATOR
            Platform.IOS -> Device.DeviceType.SIMULATOR
            Platform.WEB -> Device.DeviceType.BROWSER
        }

        return Device.Connected(
            instanceId = description,
            deviceSpec = deviceSpec,
            description = description,
            platform = platform,
            deviceType = deviceType,
        )
    }

    private fun setInput(vararg lines: String) {
        val input = lines.joinToString(separator = "\n", postfix = "\n")
        System.setIn(ByteArrayInputStream(input.toByteArray()))
    }

    private fun output(): String {
        return outputStream.toString()
            .replace("\r\n", "\n")
            .replace(ANSI_ESCAPE, "")
    }

    private companion object {
        const val PROMPT = "Please choose one (or \"q\" to quit): "
        val ANSI_ESCAPE = Regex("\\u001B\\[[;\\d]*m")
    }
}
