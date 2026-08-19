package maestro.orchestra.flow

import kotlinx.coroutines.runBlocking
import maestro.device.Platform
import maestro.orchestra.Orchestra
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.FakeDeviceProvider
import maestro.orchestra.devicecore.RealDeviceGateway
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.Path

/** Tier-2 command-driven harness: run a YAML fixture through the real Orchestra stack, faking
 *  device-core at the DeviceProvider seam. Seed evidence via [provider]. */
object FlowMatrix {
    private fun fixturePath(fixture: String): Path {
        val url = FlowMatrix::class.java.classLoader.getResource("$fixture.yaml")
            ?: error("fixture not found on the test classpath: $fixture.yaml")
        return File(url.toURI()).toPath()
    }

    fun run(
        fixture: String,
        provider: FakeDeviceProvider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) },
        env: Map<String, String> = emptyMap(),
        onLog: (List<String>) -> Unit = {},
    ): Orchestra.FlowResult {
        val path = fixturePath(fixture)
        val commands = YamlCommandReader.readCommands(path)
            .withEnv(env.withDefaultEnvVars(path.toFile()))
        val driver = RealDeviceGateway(providerFactory = { provider })
        driver.connect(DeviceCoreTarget(Platform.ANDROID), null)
        val orchestra = Orchestra(
            driver = driver,
            platform = Platform.ANDROID,
            lookupTimeoutMs = 0L,
            optionalLookupTimeoutMs = 0L,
            onCommandMetadataUpdate = { _, metadata -> onLog(metadata.logMessages) },
        )
        return runBlocking { orchestra.runFlow(commands) }
    }
}
