package maestro.cli.driver

data class DriverBuildConfig(
    val teamId: String,
    val derivedDataPath: String,
    val sourceCodePath: String = "driver/ios",
    val destination: String = "generic/platform=iphoneos",
    val architectures: String = "arm64",
    val configuration: String = "Debug",
)