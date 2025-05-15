import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import util.DeviceCtlProcess
import util.LocalIOSDevice
import java.nio.file.Files
import kotlin.io.path.writeText

class DeviceCtlResponseTest {


    @Test
    fun `test if deserializing device list works`() {
        // given
        val deviceCtlOutput = getDeviceCtlOutput()
        val deviceOutput = Files.createTempFile("output", ".json").apply {
            writeText(deviceCtlOutput)
        }
        val deviceCtlProcess = mockk<DeviceCtlProcess>()
        every { deviceCtlProcess.devicectlDevicesOutput() } returns deviceOutput.toFile()

        // when
        val connectedDevices = LocalIOSDevice(deviceCtlProcess).listDeviceViaDeviceCtl()

        // then
        assertThat(connectedDevices).isNotEmpty()
    }

    private fun getDeviceCtlOutput(): String {
       return """
           {
             "info" : {
               "arguments" : [
                 "devicectl",
                 "--json-output",
                 "./output.json",
                 "list",
                 "devices"
               ],
               "commandType" : "devicectl.list.devices",
               "environment" : {
                 "TERM" : "xterm-256color"
               },
               "jsonVersion" : 2,
               "outcome" : "success",
               "version" : "397.28"
             },
             "result" : {
               "devices" : [
                 {
                   "capabilities" : [
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.unpairdevice",
                       "name" : "Unpair Device"
                     }
                   ],
                   "connectionProperties" : {
                     "authenticationType" : "manualPairing",
                     "isMobileDeviceOnly" : false,
                     "pairingState" : "paired",
                     "potentialHostnames" : [
                       "00008110-001C108C0132401E.coredevice.local",
                       "0097E500-40E1-4842-93ED-89C7D7E25655.coredevice.local"
                     ],
                     "tunnelState" : "unavailable"
                   },
                   "deviceProperties" : {
                     "bootedFromSnapshot" : true,
                     "bootedSnapshotName" : "com.apple.os.update-5B7A8C0795233D12F5E87CF783542EF012DAA964DB278B7F6E53F49E361EB5BF",
                     "ddiServicesAvailable" : false,
                     "developerModeStatus" : "enabled",
                     "hasInternalOSBuild" : false,
                     "name" : "xx's iPhone ",
                     "rootFileSystemIsWritable" : false
                   },
                   "hardwareProperties" : {
                     "cpuType" : {
                       "name" : "arm64e",
                       "subType" : 2,
                       "type" : 16777228
                     },
                     "deviceType" : "iPhone",
                     "ecid" : 7899492849434654,
                     "hardwareModel" : "D64AP",
                     "internalStorageCapacity" : 512000000000,
                     "isProductionFused" : true,
                     "marketingName" : "iPhone 13 Pro Max",
                     "platform" : "iOS",
                     "productType" : "iPhone14,3",
                     "reality" : "physical",
                     "serialNumber" : "C7LTWMC263",
                     "supportedCPUTypes" : [
                       {
                         "name" : "arm64e",
                         "subType" : 2,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64",
                         "subType" : 0,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64",
                         "subType" : 1,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64_32",
                         "subType" : 1,
                         "type" : 33554444
                       }
                     ],
                     "supportedDeviceFamilies" : [
                       1
                     ],
                     "thinningProductType" : "iPhone14,3",
                     "udid" : "00008110-001C108C0132401E"
                   },
                   "identifier" : "0097E500-40E1-4842-93ED-89C7D7E25655",
                   "tags" : [

                   ],
                   "visibilityClass" : "default"
                 },
                 {
                   "capabilities" : [
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.unpairdevice",
                       "name" : "Unpair Device"
                     }
                   ],
                   "connectionProperties" : {
                     "authenticationType" : "manualPairing",
                     "isMobileDeviceOnly" : false,
                     "lastConnectionDate" : "2025-04-03T11:26:51.971Z",
                     "pairingState" : "paired",
                     "potentialHostnames" : [
                       "00008301-F0918DA12298C02E.coredevice.local",
                       "35C5DE54-1D3E-4144-8E9A-FA6C38B0B2F1.coredevice.local"
                     ],
                     "tunnelState" : "unavailable"
                   },
                   "deviceProperties" : {
                     "ddiServicesAvailable" : false,
                     "developerModeStatus" : "disabled",
                     "hasInternalOSBuild" : false,
                     "osBuildUpdate" : "22S560",
                     "osVersionNumber" : "11.3.1"
                   },
                   "hardwareProperties" : {
                     "cpuType" : {
                       "name" : "arm64_32",
                       "subType" : 1,
                       "type" : 33554444
                     },
                     "deviceType" : "appleWatch",
                     "ecid" : 17334792163935436846,
                     "hardwareModel" : "N187bAP",
                     "isProductionFused" : true,
                     "marketingName" : "Apple Watch Series 7",
                     "platform" : "watchOS",
                     "productType" : "Watch6,7",
                     "reality" : "physical",
                     "serialNumber" : "KFKQC1YWK1",
                     "thinningProductType" : "Watch6,7",
                     "udid" : "00008301-F0918DA12298C02E"
                   },
                   "identifier" : "35C5DE54-1D3E-4144-8E9A-FA6C38B0B2F1",
                   "tags" : [

                   ],
                   "visibilityClass" : "default"
                 },
                 {
                   "capabilities" : [
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.getlockstate",
                       "name" : "Get Lock State"
                     },
                     {
                       "featureIdentifier" : "Cryptex1,UseProductClass",
                       "name" : "com.apple.security.cryptexd.remote"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.fetchappicons",
                       "name" : "Fetch Application Icons"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.listroots",
                       "name" : "List Roots"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.spawnexecutable",
                       "name" : "Spawn Executable"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.listprocesses",
                       "name" : "List Processes"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.sendsignaltoprocess",
                       "name" : "Send Signal to Process"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.fetchddimetadata",
                       "name" : "Fetch Developer Disk Image Services Metadata"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.installroot",
                       "name" : "Install Root"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.getdisplayinfo",
                       "name" : "Get Display Information"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.installapp",
                       "name" : "Install Application"
                     },
                     {
                       "featureIdentifier" : "com.apple.dt.remoteFetchSymbols.dyldSharedCacheFiles",
                       "name" : "com.apple.dt.remoteFetchSymbols"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.launchapplication",
                       "name" : "Launch Application"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.getdeviceinfo",
                       "name" : "Fetch Extended Device Info"
                     },
                     {
                       "featureIdentifier" : "CryptexInstall",
                       "name" : "com.apple.security.cryptexd.remote"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.uninstallapp",
                       "name" : "Uninstall Application"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.disableddiservices",
                       "name" : "Disable Developer Disk Image Services"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.disconnectdevice",
                       "name" : "Disconnect from Device"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.viewdevicescreen",
                       "name" : "View Device Screen"
                     },
                     {
                       "featureIdentifier" : "ReadIdentifiers",
                       "name" : "com.apple.security.cryptexd.remote"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.sendmemorywarningtoprocess",
                       "name" : "Send Memory Warning to Process"
                     },
                     {
                       "featureIdentifier" : "com.apple.dt.profile",
                       "name" : "Service Hub Profile"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.uninstallroot",
                       "name" : "Uninstall Root"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.unpairdevice",
                       "name" : "Unpair Device"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.capturesysdiagnose",
                       "name" : "Capture Sysdiagnose"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.listapps",
                       "name" : "List Applications"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.querymobilegestalt",
                       "name" : "Query MobileGestalt"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.acquireusageassertion",
                       "name" : "Acquire Usage Assertion"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.debugserverproxy",
                       "name" : "com.apple.internal.dt.remote.debugproxy"
                     },
                     {
                       "featureIdentifier" : "Cryptex1",
                       "name" : "com.apple.security.cryptexd.remote"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.transferFiles",
                       "name" : "Transfer Files"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.monitorprocesstermination",
                       "name" : "Monitor Process for Termination"
                     },
                     {
                       "featureIdentifier" : "com.apple.coredevice.feature.rebootdevice",
                       "name" : "Reboot Device"
                     }
                   ],
                   "connectionProperties" : {
                     "authenticationType" : "manualPairing",
                     "isMobileDeviceOnly" : false,
                     "lastConnectionDate" : "2025-04-28T11:40:23.973Z",
                     "localHostnames" : [
                       "iPhone.coredevice.local",
                       "00008120-0014485601E3C01E.coredevice.local",
                       "6986451F-A2FF-48DE-A70E-45E06E1F1446.coredevice.local"
                     ],
                     "pairingState" : "paired",
                     "potentialHostnames" : [
                       "00008120-0014485601E3C01E.coredevice.local",
                       "6986451F-A2FF-48DE-A70E-45E06E1F1446.coredevice.local"
                     ],
                     "transportType" : "wired",
                     "tunnelIPAddress" : "fdcd:3155:df16::1",
                     "tunnelState" : "connected",
                     "tunnelTransportProtocol" : "tcp"
                   },
                   "deviceProperties" : {
                     "bootState" : "booted",
                     "bootedFromSnapshot" : true,
                     "bootedSnapshotName" : "com.apple.os.update-E6D6B5414ECE9974CF280499DC586E22529542ABCF3C12D2196133074DF91551",
                     "ddiServicesAvailable" : true,
                     "developerModeStatus" : "enabled",
                     "hasInternalOSBuild" : false,
                     "name" : "iPhone",
                     "osBuildUpdate" : "22E252",
                     "osVersionNumber" : "18.4.1",
                     "rootFileSystemIsWritable" : false,
                     "screenViewingURL" : "devices://device/open?id=6986451F-A2FF-48DE-A70E-45E06E1F1446"
                   },
                   "hardwareProperties" : {
                     "cpuType" : {
                       "name" : "arm64e",
                       "subType" : 2,
                       "type" : 16777228
                     },
                     "deviceType" : "iPhone",
                     "ecid" : 5709033770303518,
                     "hardwareModel" : "D73AP",
                     "internalStorageCapacity" : 128000000000,
                     "isProductionFused" : true,
                     "marketingName" : "iPhone 14 Pro",
                     "platform" : "iOS",
                     "productType" : "iPhone15,2",
                     "reality" : "physical",
                     "serialNumber" : "L7YT7HC7V5",
                     "supportedCPUTypes" : [
                       {
                         "name" : "arm64e",
                         "subType" : 2,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64",
                         "subType" : 0,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64",
                         "subType" : 1,
                         "type" : 16777228
                       },
                       {
                         "name" : "arm64_32",
                         "subType" : 1,
                         "type" : 33554444
                       }
                     ],
                     "supportedDeviceFamilies" : [
                       1
                     ],
                     "thinningProductType" : "iPhone15,2",
                     "udid" : "00008120-0014485601E3C01E"
                   },
                   "identifier" : "6986451F-A2FF-48DE-A70E-45E06E1F1446",
                   "tags" : [

                   ],
                   "visibilityClass" : "default"
                 }
               ]
             }
           }
       """.trimIndent()
    }
}