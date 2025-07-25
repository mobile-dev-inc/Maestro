package maestro.android

import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

object AndroidAppFiles {

    fun pull(dadb: Dadb, packageName: String, zipOutFile: File) {
        zipOutFile.delete()
        val zipOutUri = URI("jar:${zipOutFile.toURI().scheme}", zipOutFile.absolutePath, null)
        FileSystems.newFileSystem(zipOutUri, mapOf("create" to "true")).use { fs ->
            // Create zip directories first
            listRemoteFiles(dadb, packageName, "-type d").forEach { remoteDir ->
                val dstLocation = remoteDir
                    .removePrefix("/")
                    .removeSuffix("/.")
                    .removeSuffix("/")
                val dstPath = fs.getPath(dstLocation)
                dstPath.createDirectories()
            }

            // Create zip files
            listRemoteFiles(dadb, packageName, "-type f").forEach { remoteFile ->
                val dstLocation = remoteFile
                    .removePrefix("/")
                    .removeSuffix("/.")
                    .removeSuffix("/")
                val dstPath = fs.getPath(dstLocation)
                pullAppFile(dadb, packageName, dstPath, remoteFile)
            }
        }
    }

    fun getApkFile(dadb: Dadb, appId: String): File {
        val apkPath = dadb.shell("pm list packages -f --user 0 | grep $appId | head -1")
            .output.substringAfterLast("package:").substringBefore("=$appId")
        apkPath.substringBefore("=$appId")
        val dst = File.createTempFile("tmp", ".apk")
        dadb.pull(dst, apkPath)
        return dst
    }

    fun push(dadb: Dadb, packageName: String, appFilesZip: File) {
        val remoteZip = "/data/local/tmp/app.zip"
        dadb.push(appFilesZip, remoteZip)
        try {
            shell(dadb, "run-as $packageName unzip -o -d / $remoteZip")
        } finally {
            shell(dadb, "rm $remoteZip")
        }
    }

    private fun pullAppFile(dadb: Dadb, packageName: String, localPath: Path, remotePath: String) {
        dadb.open("exec:run-as $packageName cat $remotePath").use { stream ->
            Files.copy(stream.source.inputStream(), localPath)
        }
    }

    private fun listRemoteFiles(dadb: Dadb, packageName: String, options: String): List<String> {
        val result = shell(dadb, "run-as $packageName find $options")
        val appDataDir = "/data/data/$packageName"
        return result.lines()
            .filter { it.isNotBlank() }
            .map { "$appDataDir/${it.removePrefix("./")}" }
    }

    private fun shell(dadb: Dadb, command: String): String {
        val response = dadb.shell(command)
        if (response.exitCode != 0) throw IOException("Shell command failed ($command):\n${response.allOutput}")
        return response.allOutput
    }
}
