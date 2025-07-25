package maestro.android

import maestro_android.MaestroAndroid

object AndroidLaunchArguments {

    fun Map<String, Any>.toAndroidLaunchArguments(): List<MaestroAndroid.ArgumentValue> {
        return toList().map {
            when (val value = it.second) {
                is Boolean -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(Boolean::class.java.name)
                    .build()
                is Int -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(Int::class.java.name)
                    .build()
                is Double -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(Double::class.java.name)
                    .build()
                is Long -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(Long::class.java.name)
                    .build()
                is String -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(String::class.java.name)
                    .build()
                else -> MaestroAndroid.ArgumentValue.newBuilder()
                    .setKey(it.first)
                    .setValue(value.toString())
                    .setType(String::class.java.name)
                    .build()
            }
        }
    }
}