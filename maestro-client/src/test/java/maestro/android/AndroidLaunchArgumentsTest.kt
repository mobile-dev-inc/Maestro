package maestro.android

import com.google.common.truth.Truth.assertThat
import maestro.android.AndroidLaunchArguments.toAndroidLaunchArguments
import maestro_android.MaestroAndroid
import org.junit.jupiter.api.Test

class AndroidLaunchArgumentsTest {

    @Test
    fun `it correctly parses to android launch arguments`() {
        // given
        val arguments = mapOf<String, Any>(
            "isMaestro" to true,
            "cartValue" to 4,
            "cartValueDouble" to 4.4,
            "cartColor" to "Hello this is cart value which is orange",
            "cartTimeStamp" to 1683113805263,
            "cartZeroValue" to 0
        )

        // when
        val launchArguments = arguments.toAndroidLaunchArguments()

        // then
        assertThat(launchArguments).isEqualTo(
            listOf(
                provideArgumentValue("isMaestro", "true", Boolean::class.java.name),
                provideArgumentValue("cartValue", "4", Int::class.java.name),
                provideArgumentValue("cartValueDouble", "4.4", Double::class.java.name),
                provideArgumentValue("cartColor", "Hello this is cart value which is orange", String::class.java.name),
                provideArgumentValue("cartTimeStamp", "1683113805263", Long::class.java.name),
                provideArgumentValue("cartZeroValue", "0", Int::class.java.name)
            )
        )
    }

    private fun provideArgumentValue(key: String, value: String, type: String): MaestroAndroid.ArgumentValue {
        return MaestroAndroid.ArgumentValue.newBuilder()
            .setKey(key)
            .setValue(value)
            .setType(type)
            .build()
    }
}