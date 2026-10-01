package xcuitest.crash

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IOSAppTerminationFinderTest {

    // Lines as runningboardd logged them on iOS 17.5, 18.2 and 26.0 simulators.
    private val crash =
        "2026-10-01 15:02:11.404 Df runningboardd[5993:db8454] [com.apple.runningboard:ttl] " +
            "[app<com.example.app((null))>:6256] termination reported by launchd (2, 5, 5)"
    private val uncaughtException =
        "2026-09-26 13:26:56.379170-0700  localhost runningboardd[20526]: (RunningBoard) [com.apple.runningboard:ttl] " +
            "[app<com.example.app((null))>:21249] termination reported by launchd (2, 6, 6)"
    private val helperProcessOfTheApp =
        "2026-09-26 13:26:56.380953-0700  localhost runningboardd[20526]: (RunningBoard) [com.apple.runningboard:ttl] " +
            "[xpcservice<com.apple.WebKit.WebContent([app<com.example.app((null))>:21249])>{vt hash: 71395982}" +
            "[uuid:73F78BD3-4534-458B-8203-E22838B343D0]:21255] termination reported by launchd (0, 0, 0)"
    private val forceQuit =
        "2026-10-01 15:02:15.880 Df runningboardd[5993:db805a] [com.apple.runningboard:ttl] " +
            "[app<com.example.app((null))>:6296] termination reported by launchd (10, 4227595259, 9)"
    private val killed =
        "2026-10-01 15:02:19.100 Df runningboardd[5993:db805a] [com.apple.runningboard:ttl] " +
            "[app<com.example.app((null))>:6310] termination reported by launchd (2, 9, 9)"
    private val otherApp =
        "2026-10-01 15:02:16.001 Df runningboardd[5993:db805a] [com.apple.runningboard:ttl] " +
            "[app<com.example.other((null))>:6300] termination reported by launchd (2, 11, 11)"

    private fun finder(vararg lines: String) = IOSAppTerminationFinder(readLog = { _, _ -> lines.joinToString("\n") })

    @Test
    fun `a process ended by a fault signal is a crash`() {
        val terminations = finder(crash).find("SIM", "com.example.app", sinceEpochMs = 0)

        assertThat(terminations).containsExactly(AppTermination(pid = 6256, domain = 2, code = 5))
        assertThat(terminations!!.single().isCrash).isTrue()
        assertThat(terminations.single().summary).isEqualTo("App crashed (SIGTRAP)")
    }

    @Test
    fun `an uncaught exception aborts the app and is a crash`() {
        val terminations = finder(helperProcessOfTheApp, uncaughtException, helperProcessOfTheApp)
            .find("SIM", "com.example.app", sinceEpochMs = 0)

        // The app's helper processes exiting alongside it are not the app.
        assertThat(terminations).containsExactly(AppTermination(pid = 21249, domain = 2, code = 6))
        assertThat(terminations!!.single().summary).isEqualTo("App crashed (SIGABRT)")
    }

    @Test
    fun `an app that was closed on purpose is not a crash`() {
        assertThat(finder(forceQuit).find("SIM", "com.example.app", sinceEpochMs = 0)!!.single().isCrash).isFalse()
    }

    @Test
    fun `an app that was killed is not counted as a crash`() {
        assertThat(finder(killed).find("SIM", "com.example.app", sinceEpochMs = 0)!!.single().isCrash).isFalse()
    }

    @Test
    fun `only the requested app is reported, oldest first`() {
        val terminations = finder(forceQuit, otherApp, crash).find("SIM", "com.example.app", sinceEpochMs = 0)

        assertThat(terminations!!.map { it.pid }).containsExactly(6296, 6256).inOrder()
    }

    @Test
    fun `an app that is still running has no terminations`() {
        assertThat(finder().find("SIM", "com.example.app", sinceEpochMs = 0)).isEmpty()
    }

    @Test
    fun `a log that cannot be read is reported as unknown, not as no crash`() {
        val finder = IOSAppTerminationFinder(readLog = { _, _ -> throw IllegalStateException("simctl failed") })

        assertThat(finder.find("SIM", "com.example.app", sinceEpochMs = 0)).isNull()
    }
}
