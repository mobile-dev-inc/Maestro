package maestro.android

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

object AndroidAppFiles {

    fun pull(connection: AndroidDeviceConnection, packageName: String, zipOutFile: File) {
        zipOutFile.delete()
        val zipOutUri = URI("jar:${zipOutFile.toURI().scheme}", zipOutFile.absolutePath, null)
        FileSystems.newFileSystem(zipOutUri, mapOf("create" to "true")).use { fs ->
            // Create zip directories first
            listRemoteFiles(connection, packageName, "-type d").forEach { remoteDir ->
                val dstLocation = remoteDir
                    .removePrefix("/")
                    .removeSuffix("/.")
                    .removeSuffix("/")
                val dstPath = fs.getPath(dstLocation)
                dstPath.createDirectories()
            }

            // Create zip files
            listRemoteFiles(connection, packageName, "-type f").forEach { remoteFile ->
                val dstLocation = remoteFile
                    .removePrefix("/")
                    .removeSuffix("/.")
                    .removeSuffix("/")
                val dstPath = fs.getPath(dstLocation)
                pullAppFile(connection, packageName, dstPath, remoteFile)
            }
        }
    }

    fun getApkFile(connection: AndroidDeviceConnection, appId: String): File {
        val apkPath = connection.shell("pm list packages -f --user 0 | grep $appId | head -1")
            .orThrow().substringAfterLast("package:").substringBefore("=$appId")
        if (apkPath.isBlank()) {
            throw AndroidOperationFailedException("No APK path found for package $appId")
        }
        val dst = File.createTempFile("tmp", ".apk")
        connection.pull(dst, apkPath).orThrowOnFailure()
        return dst
    }

    fun push(connection: AndroidDeviceConnection, packageName: String, appFilesZip: File) {
        val remoteZip = "/data/local/tmp/app.zip"
        connection.push(appFilesZip, remoteZip).orThrowOnFailure()
        try {
            shell(connection, "run-as $packageName unzip -o -d / $remoteZip")
        } finally {
            shell(connection, "rm $remoteZip")
        }
    }

    private fun pullAppFile(connection: AndroidDeviceConnection, packageName: String, localPath: Path, remotePath: String) {
        connection.open("exec:run-as $packageName cat $remotePath").use { stream ->
            Files.copy(stream.source.inputStream(), localPath)
        }
    }

    private fun listRemoteFiles(connection: AndroidDeviceConnection, packageName: String, options: String): List<String> {
        val result = shell(connection, "run-as $packageName find $options")
        val appDataDir = "/data/data/$packageName"
        return result.lines()
            .filter { it.isNotBlank() }
            .map { "$appDataDir/${it.removePrefix("./")}" }
    }

    // Delegate the throw-on-failure to the connection's AdbShellResponse.orThrow(); a transport death
    // already surfaces as a Device*Exception from connection.shell and is never reclassified here.
    private fun shell(connection: AndroidDeviceConnection, command: String): String =
        connection.shell(command).orThrow()
}
