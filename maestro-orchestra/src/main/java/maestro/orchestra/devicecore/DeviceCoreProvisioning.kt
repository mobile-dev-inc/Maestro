package maestro.orchestra.devicecore

import maestro.device.Platform

/**
 * The CLI-free provisioning entry point for the device-core execution seam. A consumer that has
 * ALREADY resolved a device — it knows the [Platform] and (optionally) the concrete serial, because
 * it booted the emulator/simulator itself — uses this to obtain a connected [DeviceCoreDriver]
 * without depending on `maestro-cli`.
 *
 * This is the entire provisioning the `maestro test` path performs on top of the seam: build a
 * [RealDeviceCoreDriver], [DeviceCoreDriver.connect] it to the resolved [DeviceCoreTarget], and
 * [DeviceCoreDriver.close] it when done. Device SELECTION (deciding WHICH booted device to talk to,
 * `adb`/`simctl` discovery, interactive pick prompts) is a consumer/CLI concern and deliberately
 * stays out of here — it lives in `maestro-cli`'s `MaestroSessionManager` and never in
 * `maestro-orchestra`.
 *
 * The connected driver is passed to `maestro.orchestra.Orchestra(driver = ...)` to execute a flow.
 * MaestroWorker (separate repo, `copilot/maestro-worker`) is the primary external consumer — see
 * `docs/devicecore-worker-migration.md`.
 */
object DeviceCoreProvisioning {

    /**
     * Build and [DeviceCoreDriver.connect] a driver for an already-resolved device, then return it.
     * The caller OWNS the driver lifecycle and MUST [DeviceCoreDriver.close] it when done — prefer
     * [withSession] when the work is scoped, which closes for you.
     *
     * [serial] is the resolved target serial (Android adb serial e.g. `emulator-5554`, iOS simulator
     * udid); null lets device-core pick the single attached device. [appId] carries the iOS bundle id
     * through connect (device-core reads it at connect time; ignored for Android). [driverFactory]
     * defaults to the real device-core-backed driver; tests inject a fake.
     */
    fun connect(
        platform: Platform,
        serial: String? = null,
        appId: String? = null,
        driverFactory: () -> DeviceCoreDriver = { RealDeviceCoreDriver() },
    ): DeviceCoreDriver {
        val driver = driverFactory()
        driver.connect(DeviceCoreTarget(platform, serial), appId)
        return driver
    }

    /**
     * [connect] a driver for an already-resolved device, run [block] with it, and [DeviceCoreDriver.close]
     * it in a `finally` — including when [block] or `connect` itself throws. This is the shape the CLI's
     * own session manager and the MCP session manager already use; it's the recommended way to drive a
     * scoped flow through [maestro.orchestra.Orchestra].
     */
    fun <T> withSession(
        platform: Platform,
        serial: String? = null,
        appId: String? = null,
        driverFactory: () -> DeviceCoreDriver = { RealDeviceCoreDriver() },
        block: (driver: DeviceCoreDriver) -> T,
    ): T {
        val driver = driverFactory()
        return try {
            driver.connect(DeviceCoreTarget(platform, serial), appId)
            block(driver)
        } finally {
            driver.close()
        }
    }
}
