package ios.devicectl

import util.CommandLineUtils

class DeviceCtlAppLauncher {
   fun launch(deviceId: String, bundleId: String, launchArguments: List<String>) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "process",
                "launch",
                "--terminate-existing",
                "--device",
                deviceId,
                bundleId,
            ) + launchArguments,
        )
    }
}
