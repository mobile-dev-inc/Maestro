package maestro.conformance.fixture

data class FixtureApp(val framework: String, val appId: String, val apkResource: String)

object FixtureCatalog {
    val native = FixtureApp("native", "dev.mobile.maestro.fixture", "/native-fixture.apk")
    fun byName(name: String): FixtureApp = when (name) {
        "native" -> native
        else -> error("Unknown framework: $name (Phase 1 ships only 'native')")
    }
}
