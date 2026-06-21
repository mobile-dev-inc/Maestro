package maestro.conformance.fixture

data class FixtureApp(val framework: String, val appId: String, val apkResource: String)

object FixtureCatalog {
    val native = FixtureApp("native", "dev.mobile.maestro.fixture", "/native-fixture.apk")
    val compose = FixtureApp("compose", "dev.mobile.maestro.fixture.compose", "/compose-fixture.apk")
    // React Native fixture: standalone RN app (conformance-fixtures/react-native), built into a
    // committed APK by build-rn-fixture.sh (not an on-demand Gradle module — see that script).
    val reactNative = FixtureApp("react-native", "dev.mobile.maestro.fixture.rn", "/react-native-fixture.apk")
    fun byName(name: String): FixtureApp = when (name) {
        "native" -> native
        "compose" -> compose
        "react-native" -> reactNative
        else -> error("Unknown framework: $name (supported: native, compose, react-native)")
    }
}
