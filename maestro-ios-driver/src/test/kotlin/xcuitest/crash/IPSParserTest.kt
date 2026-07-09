package xcuitest.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class IPSParserTest {

    @Nested
    inner class Parse {

        @Test
        fun `parses EXC_BREAKPOINT SIGTRAP crash`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_BREAKPOINT", result!!.exceptionType)
            assertEquals("SIGTRAP", result.signal)
        }

        @Test
        fun `parses EXC_CRASH SIGKILL crash with termination`() {
            val content = loadResource("ios/crashes/exc_crash_sigkill.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_CRASH", result!!.exceptionType)
            assertEquals("SIGKILL", result.signal)
            assertEquals("XPC_EXIT_REASON_SIGTERM_TIMEOUT", result.terminationReason)
        }

        @Test
        fun `parses EXC_CRASH SIGABRT crash with termination`() {
            val content = loadResource("ios/crashes/exc_crash_sigabrt.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_CRASH", result!!.exceptionType)
            assertEquals("SIGABRT", result.signal)
            assertEquals("Abort trap: 6", result.terminationReason)
        }

        @Test
        fun `parses EXC_BAD_ACCESS SIGSEGV crash with subtype`() {
            val content = loadResource("ios/crashes/exc_bad_access_sigsegv.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_BAD_ACCESS", result!!.exceptionType)
            assertEquals("SIGSEGV", result.signal)
            assertEquals("KERN_INVALID_ADDRESS at 0x00000000130e0a20", result.exceptionSubtype)
        }

        @Test
        fun `extracts simulator UUID from coalitionName`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("TEST-SIM-UUID-1234", result!!.simulatorId)
        }

        @Test
        fun `extracts different simulator UUID`() {
            val content = loadResource("ios/crashes/different_simulator.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("OTHER-SIM-UUID-5678", result!!.simulatorId)
        }

        @Test
        fun `extracts bundle ID`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("com.example.testapp", result!!.bundleId)
        }

        @Test
        fun `extracts process name`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("TestApp", result!!.processName)
        }

        @Test
        fun `extracts crash timestamp from IPS header`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals(
                Instant.parse("2026-02-05T06:50:20Z").toEpochMilli(),
                result!!.timestampEpochMs,
            )
        }
    }

    @Nested
    inner class FriendlyMessage {

        @Test
        fun `generates friendly message without termination`() {
            val content = loadResource("ios/crashes/exc_breakpoint_sigtrap.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_BREAKPOINT (SIGTRAP)", result!!.friendlyMessage)
        }

        @Test
        fun `generates friendly message with termination reason`() {
            val content = loadResource("ios/crashes/exc_crash_sigkill.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_CRASH (SIGKILL) - XPC_EXIT_REASON_SIGTERM_TIMEOUT", result!!.friendlyMessage)
        }

        @Test
        fun `generates friendly message with abort trap`() {
            val content = loadResource("ios/crashes/exc_crash_sigabrt.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_CRASH (SIGABRT) - Abort trap: 6", result!!.friendlyMessage)
        }

        @Test
        fun `generates friendly message with memory address for EXC_BAD_ACCESS`() {
            val content = loadResource("ios/crashes/exc_bad_access_sigsegv.ips")
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_BAD_ACCESS (SIGSEGV) - KERN_INVALID_ADDRESS at 0x00000000130e0a20", result!!.friendlyMessage)
        }
    }

    @Nested
    inner class InvalidInput {

        @Test
        fun `returns null for empty content`() {
            assertNull(IPSParser.parse(""))
        }

        @Test
        fun `returns null for blank content`() {
            assertNull(IPSParser.parse("   "))
        }

        @Test
        fun `returns null for non-JSON content`() {
            assertNull(IPSParser.parse("not json at all"))
        }

        @Test
        fun `returns null for JSON without exception field`() {
            val content = """{"app_name":"TestApp","bundleID":"com.example.testapp"}
{
  "procName": "TestApp",
  "coalitionName": "com.apple.CoreSimulator.SimDevice.TEST-UUID"
}"""
            assertNull(IPSParser.parse(content))
        }

        @Test
        fun `returns null for JSON with incomplete exception`() {
            val content = """{"app_name":"TestApp"}
{
  "procName": "TestApp",
  "exception": {"type":"EXC_CRASH"}
}"""
            // Missing signal
            assertNull(IPSParser.parse(content))
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `handles coalitionName without simulator prefix`() {
            val content = """{"app_name":"TestApp","bundleID":"com.example.testapp"}
{
  "procName": "TestApp",
  "coalitionName": "com.example.testapp",
  "exception": {"type":"EXC_CRASH","signal":"SIGKILL"}
}"""
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertNull(result!!.simulatorId)
        }

        @Test
        fun `handles missing coalitionName`() {
            val content = """{"app_name":"TestApp","bundleID":"com.example.testapp"}
{
  "procName": "TestApp",
  "exception": {"type":"EXC_CRASH","signal":"SIGKILL"}
}"""
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertNull(result!!.simulatorId)
        }

        @Test
        fun `extracts bundle ID from bundleInfo when header bundleID missing`() {
            val content = """{"app_name":"TestApp"}
{
  "procName": "TestApp",
  "bundleInfo": {"CFBundleIdentifier":"com.example.frominfo"},
  "exception": {"type":"EXC_CRASH","signal":"SIGKILL"}
}"""
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("com.example.frominfo", result!!.bundleId)
        }

        @Test
        fun `uses app_name as fallback for processName`() {
            val content = """{"app_name":"FallbackName","bundleID":"com.example.testapp"}
{
  "exception": {"type":"EXC_CRASH","signal":"SIGKILL"}
}"""
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("FallbackName", result!!.processName)
        }

        @Test
        fun `handles single-line compact JSON`() {
            val content = """{"app_name":"TestApp","bundleID":"com.example.testapp","procName":"TestApp","coalitionName":"com.apple.CoreSimulator.SimDevice.UUID-123","exception":{"type":"EXC_CRASH","signal":"SIGKILL"}}"""
            val result = IPSParser.parse(content)

            assertNotNull(result)
            assertEquals("EXC_CRASH", result!!.exceptionType)
            assertEquals("UUID-123", result.simulatorId)
        }
    }

    private fun loadResource(path: String): String {
        return this::class.java.classLoader.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }
}
