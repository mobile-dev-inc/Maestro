package maestro.cli.command.network

import maestro.cli.device.Device
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.device.Platform
import util.LocalSimulatorUtils
import util.LocalSimulatorUtils.SimctlError
import maestro.cli.CliError
import maestro.cli.view.red
import maestro.networkproxy.NetworkProxy
import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "setup",
)
class SetupForProxyCommand : Callable<Int> {

    @CommandLine.ParentCommand
    lateinit var parent: NetworkCommand

    override fun call(): Int {
        println()
        println("Network Mocking has been deprecated and will be removed in a future version".red())
        println()

        if (parent.app.host != null) {
            error("Automatic proxy setup of remote devices is not supported. Please configure the device manually.")
        }

        val device = PickDeviceInteractor.pickDevice(parent.app.deviceId)

        when (device.platform) {
            Platform.IOS -> setupIOS(device)
            Platform.ANDROID -> setupAndroid()
            Platform.WEB -> error("Proxy setup is not supported for web devices")
        }

        return 0
    }

    private fun setupAndroid() {
        println()
        println("Please follow the documentation page on how to setup network mocking for Android:")
        println("https://maestro.mobile.dev/advanced/network-mocking/android")
        println()
    }

    private fun setupIOS(device: Device.Connected) {
        val certFile = NetworkProxy.unpackPemCertificates()

        try {
            LocalSimulatorUtils.addTrustedCertificate(
                device.instanceId,
                certFile,
            )
        } catch (e: SimctlError) {
            throw CliError(e.message)
        }
    }

}
