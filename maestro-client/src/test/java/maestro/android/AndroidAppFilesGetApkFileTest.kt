package maestro.android

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.drivers.DadbConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

internal class AndroidAppFilesGetApkFileTest {

    @Test
    fun `getApkFile throws a non-transport error when the app is not installed`() {
        val dadb = mockk<DadbConnection>(relaxed = true)
        // `pm list packages` finds nothing for a non-existent app -> blank output -> blank apkPath.
        every { dadb.shell(any()) } returns mockk { every { output } returns "" }

        val error = assertThrows<IllegalStateException> {
            AndroidAppFiles.getApkFile(dadb, "non.existent.app.id")
        }

        assertThat(error).hasMessageThat().contains("non.existent.app.id")
        // Must NOT pull a bogus path: that IOException is translated to DeviceUnreachableException,
        // which bypasses `optional` suppression and fails the flow terminally.
        verify(exactly = 0) { dadb.pull(any<File>(), any<String>()) }
    }
}
