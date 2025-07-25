package maestro.utils

import com.google.common.truth.Truth.assertThat
import maestro.utils.HttpUtils.toMultipartBody
import okhttp3.MultipartBody
import org.junit.jupiter.api.Test

internal class HttpUtilsTest {

    @Test
    internal fun `toMultipartBody should successfully parse a map containing a filePath along with mediaType`() {
        // Given
        val map = mapOf(
            "uploadType" to "import",
            "data" to (listOf(
                "filePath" to "testFilePath",
                "mediaType" to "text/csv"
            ))
        )

        // When
        val multipartBody = map.toMultipartBody()

        // Then
        assertThat(multipartBody.size).isEqualTo(2)
        assertThat(multipartBody.type).isEqualTo(MultipartBody.FORM)
    }

    @Test
    internal fun `toMultipartBody should successfully parse a map containing a filePath without mediaType`() {
        // Given
        val map = mapOf(
            "uploadType" to "import",
            "data" to (listOf(
                "filePath" to "testFilePath"
            ))
        )

        // When
        val multipartBody = map.toMultipartBody()

        // Then
        assertThat(multipartBody.size).isEqualTo(2)
        assertThat(multipartBody.type).isEqualTo(MultipartBody.FORM)
    }

    @Test
    internal fun `toMultipartBody should successfully parse a map without a filePath`() {
        // Given
        val map = mapOf(
            "data1" to "test1",
            "data2" to "test2",
            "data3" to "test3",
        )

        // When
        val multipartBody = map.toMultipartBody()

        // Then
        assertThat(multipartBody.size).isEqualTo(3)
        assertThat(multipartBody.type).isEqualTo(MultipartBody.FORM)
    }
}