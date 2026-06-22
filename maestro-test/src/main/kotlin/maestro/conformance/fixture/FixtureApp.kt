package maestro.conformance.fixture

data class FixtureApp(val framework: String, val appId: String, val apkResource: String)

object FixtureCatalog {
    val native = FixtureApp("native", "dev.mobile.maestro.fixture", "/native-fixture.apk")
    val compose = FixtureApp("compose", "dev.mobile.maestro.fixture.compose", "/compose-fixture.apk")
    fun byName(name: String): FixtureApp = when (name) {
        "native" -> native
        "compose" -> compose
        else -> error("Unknown framework: $name (supported: native, compose)")
    }
}
