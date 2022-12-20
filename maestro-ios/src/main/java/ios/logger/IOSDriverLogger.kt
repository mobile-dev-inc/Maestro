package ios.logger

import ios.commands.CommandLineUtils
import net.harawata.appdirs.AppDirsFactory
import java.io.File

internal object IOSDriverLogger {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private val logDirectory = File(AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR))

    fun dumpDeviceLogs(deviceId: String?) {
        checkNotNull(deviceId) { "device id is null, seem like you are not connected with iOS device" }
        val homePath = System.getProperty("user.home")
        val logArchivePath = "$homePath/Library/Logs/CoreSimulator/$deviceId/system_logs.logarchive"
        CommandLineUtils.runCommand("rm -r -f $logArchivePath")
        CommandLineUtils.runCommand("xcrun simctl spawn booted log collect")
        CommandLineUtils.runCommand("mv $logArchivePath $logDirectory")
    }
}