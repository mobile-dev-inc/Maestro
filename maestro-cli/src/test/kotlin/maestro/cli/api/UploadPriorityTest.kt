package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UploadPriorityTest {

    @Test
    fun `fromApiName matches any case, rejects unknown values, and apiNames lists the wire form`() {
        assertThat(UploadPriority.fromApiName("low")).isEqualTo(UploadPriority.LOW)
        assertThat(UploadPriority.fromApiName("NORMAL")).isEqualTo(UploadPriority.NORMAL)
        assertThat(UploadPriority.fromApiName("High")).isEqualTo(UploadPriority.HIGH)
        assertThat(UploadPriority.fromApiName("urgent")).isNull()
        assertThat(UploadPriority.apiNames).isEqualTo("low, normal, high")
    }
}
