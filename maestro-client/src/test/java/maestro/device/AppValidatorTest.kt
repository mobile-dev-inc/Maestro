package maestro.device

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AppValidatorTest {

    @Test
    fun `android validation rejects APK without arm64-v8a architecture`() {
        assertThrows<IllegalArgumentException> {
            AppValidator.fromAndroidMetadata(
                packageId = "com.example.app",
                supportedArchitectures = listOf("x86_64"),
            )
        }
    }

    @Test
    fun `android validation accepts APK with arm64-v8a among architectures`() {
        val result = AppValidator.fromAndroidMetadata(
            packageId = "com.example.app",
            supportedArchitectures = listOf("arm64-v8a", "x86_64"),
        )
        assertEquals("com.example.app", result.appIdentifier)
    }
}
