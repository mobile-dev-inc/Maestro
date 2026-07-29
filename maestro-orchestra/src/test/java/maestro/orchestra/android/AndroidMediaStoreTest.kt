package maestro.orchestra.android

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.android.AndroidDeviceConnection
import maestro.drivers.AndroidDriver
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths

@Disabled
class AndroidMediaStoreTest {

    @ParameterizedTest
    @MethodSource("provideMediaFlows")
    fun `it should add media for android and its visible in google photos`(mediaMap: Map<String, String>) {
        runBlocking {
            // given
            val expectedMediaPath = mediaMap.values.first()
            val mediaFlow = mediaMap.keys.first()
            val connection = AndroidDeviceConnection.open("localhost", 5555)
            val maestro = Maestro.android(AndroidDriver(connection))
            val maestroCommands = YamlCommandReader.readCommands(Paths.get(mediaFlow))

            // when
            Orchestra(maestro).runFlow(maestroCommands)

            // then
            val exists = connection.fileExists(expectedMediaPath)
            assertThat(exists).isTrue()
        }
    }

    @Test
    fun `it should add multiple media files`() {
        runBlocking {
            // given
            val flowPath = Paths.get("./src/test/resources/media/android/add_multiple_media.yaml")
            val connection = AndroidDeviceConnection.open("localhost", 5555)
            val maestro = Maestro.android(AndroidDriver(connection))
            val maestroCommands = YamlCommandReader.readCommands(flowPath)

            // when
            Orchestra(maestro).runFlow(maestroCommands)

            // then
            val pngExists = connection.fileExists("/sdcard/Pictures/android.png")
            val gifExists = connection.fileExists("/sdcard/Pictures/android_gif.gif")
            val mp4Exists = connection.fileExists("/sdcard/Movies/sample_video.mp4")
            assertThat(pngExists).isTrue()
            assertThat(mp4Exists).isTrue()
            assertThat(gifExists).isTrue()
        }
    }

    companion object {
        @JvmStatic
        fun provideMediaFlows(): List<Map<String, String>> {
            return listOf(
                mapOf("./src/test/resources/media/android/add_media_png.yaml" to "/sdcard/Pictures/android.png"),
                mapOf("./src/test/resources/media/android/add_media_jpeg.yaml" to "/sdcard/Pictures/android_jpeg.jpeg"),
                mapOf("./src/test/resources/media/android/add_media_jpg.yaml" to "/sdcard/Pictures/android_jpg.jpg"),
                mapOf("./src/test/resources/media/android/add_media_gif.yaml" to "/sdcard/Pictures/android_gif.gif"),
                mapOf("./src/test/resources/media/android/add_media_mp4.yaml" to "/sdcard/Movies/sample_video.mp4"),
            )
        }
    }
}