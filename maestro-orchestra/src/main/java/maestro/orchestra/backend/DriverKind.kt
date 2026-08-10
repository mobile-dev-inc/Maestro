package maestro.orchestra.backend

/**
 * Which device driver a run provisions. Maestro's own Android driver and device-core's driver both
 * claim the singleton Android UiAutomation, so exactly one is chosen ONCE, before any provisioning,
 * from the statically-known target platform + the device-core opt-in — never from a live device RPC.
 */
enum class DriverKind { MAESTRO, DEVICECORE }
