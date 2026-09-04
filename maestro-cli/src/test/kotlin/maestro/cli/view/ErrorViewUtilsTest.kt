package maestro.cli.view

import com.google.common.truth.Truth.assertThat
import maestro.DeviceDiagnostics
import maestro.DeviceUnreachableException
import maestro.android.DeviceAuthException
import maestro.android.DeviceServerDiedException
import org.junit.jupiter.api.Test
import java.io.IOException

class ErrorViewUtilsTest {

    @Test
    fun `device unreachable (iOS path, no diagnostics) maps to its concise message`() {
        val e = DeviceUnreachableException(
            operation = "inputText",
            cause = IOException("Failed to connect to /127.0.0.1:55555"),
        )

        val message = ErrorViewUtils.exceptionToMessage(e)

        assertThat(message).isEqualTo("Device became unreachable during inputText")
    }

    @Test
    fun `device unreachable (Android path, with diagnostics) maps to its concise message`() {
        val e = DeviceUnreachableException(
            operation = "shell",
            cause = IOException("connection reset"),
            diagnostics = someDiagnostics(),
        )

        val message = ErrorViewUtils.exceptionToMessage(e)

        assertThat(message).isEqualTo(
            "Device emulator-5554 is unreachable during 'shell' " +
                "(120ms since last byte, connection age 4500ms): AdbException: connection reset"
        )
    }

    @Test
    fun `device server died maps to its concise message`() {
        val e = DeviceServerDiedException(
            diagnostics = someDiagnostics(),
            cause = IOException("UNAVAILABLE"),
        )

        val message = ErrorViewUtils.exceptionToMessage(e)

        assertThat(message).isEqualTo(
            "Device server died during 'shell' on emulator-5554 " +
                "(120ms since last byte, connection age 4500ms): AdbException: connection reset"
        )
    }

    @Test
    fun `device auth failure maps to its concise message`() {
        val e = DeviceAuthException(
            serial = "emulator-5554",
            cause = IOException("unauthorized"),
        )

        val message = ErrorViewUtils.exceptionToMessage(e)

        assertThat(message).isEqualTo(
            "Device emulator-5554 is unauthorized; accept the ADB authorization prompt on the device"
        )
    }

    @Test
    fun `unexpected exceptions still map to a full stacktrace`() {
        val e = RuntimeException("boom")

        val message = ErrorViewUtils.exceptionToMessage(e)

        assertThat(message).contains("java.lang.RuntimeException: boom")
        assertThat(message).contains("\tat ")
    }

    private fun someDiagnostics() = DeviceDiagnostics(
        operation = "shell",
        rootCause = "AdbException: connection reset",
        serial = "emulator-5554",
        msSinceLastByte = 120,
        connectionAgeMs = 4500,
    )
}
