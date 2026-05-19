package maestro.cli.util

fun studioDownloadUrlForCurrentOs(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "https://studio.maestro.dev/MaestroStudio.dmg"
        os.contains("win") -> "https://studio.maestro.dev/MaestroStudio.exe"
        else -> "https://studio.maestro.dev/MaestroStudio.AppImage"
    }
}
