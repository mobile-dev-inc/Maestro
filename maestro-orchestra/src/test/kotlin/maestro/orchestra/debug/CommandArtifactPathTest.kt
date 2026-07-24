package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CommandArtifactPathTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun validate(name: String, path: String, bundled: Boolean, expectedError: String?) {
        if (expectedError == null) {
            CommandArtifactPath.validate(path, "startRecording", bundled)
            return
        }
        val e = assertThrows<MaestroException.InvalidCommand> {
            CommandArtifactPath.validate(path, "startRecording", bundled)
        }
        assertThat(e.message).contains("startRecording")
        assertThat(e.message).contains(expectedError)
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of("plain file name", "clip", true, null),
            Arguments.of("nested path inside bundle", "login/home", true, null),
            // Absolute and `..` are only wrong when there is a bundle to escape; `maestro test`
            // without one has always written wherever the flow asks.
            Arguments.of("absolute path, no bundle", "/tmp/clip", false, null),
            Arguments.of("parent segment, no bundle", "../clip", false, null),
            Arguments.of("blank path", "", false, "empty"),
            // No file name is what an unresolved variable leaves; rejected in either mode.
            Arguments.of("no file name, bundled", "logs/screenshots/", true, "file name"),
            Arguments.of("no file name, no bundle", "logs/screenshots/", false, "file name"),
            Arguments.of("absolute path, bundled", "/tmp/clip", true, "output bundle"),
            Arguments.of("parent segment leading, bundled", "../logs/screenshots/clip", true, ".."),
            Arguments.of("parent segment mid-path, bundled", "logs/../../clip", true, ".."),
            Arguments.of("backslash parent, bundled", "..\\clip", true, ".."),
        )
    }
}
