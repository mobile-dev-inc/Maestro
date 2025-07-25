import kotlin.reflect.KClass

fun readResourceAsText(cls: KClass<*>, path: String): String {
    val resourceStream = cls::class.java.getResourceAsStream(path)
        ?: throw IllegalStateException("Could not find $path in resources")

    return resourceStream.bufferedReader().use { it.readText() }
}
