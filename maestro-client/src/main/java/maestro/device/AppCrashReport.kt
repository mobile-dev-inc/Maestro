package maestro.device

/**
 * A crash of the app under test, as the device recorded it.
 *
 * @param message a one-line reason, e.g. `EXC_BREAKPOINT (SIGTRAP)`
 * @param content the device's own crash report
 */
data class AppCrashReport(val message: String, val content: String)
