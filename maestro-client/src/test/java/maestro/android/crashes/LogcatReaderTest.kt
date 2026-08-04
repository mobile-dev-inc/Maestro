package maestro.android.crashes

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class LogcatReaderTest {

    @Nested
    inner class FindCrashes {

        @Test
        fun `parses single crash with FATAL EXCEPTION`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "28715",
                exceptionType = "java.lang.NullPointerException",
                exceptionMessage = "Attempt to invoke virtual method on null object",
                stackTrace = listOf(
                    "at com.example.app.MainActivity.onCreate(MainActivity.kt:42)",
                    "at android.app.Activity.performCreate(Activity.java:8051)"
                )
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            val crash = result.crashes[0]
            assertThat(crash.name).isEqualTo("FATAL EXCEPTION:")
            assertThat(crash.cause).contains("NullPointerException")
            assertThat(crash.stackTrace).contains("MainActivity.onCreate")
        }

        @Test
        fun `parses multiple crashes and captures all`() {
            val crash1 = buildLogcatCrash(
                timestamp = "01-15 10:30:00.000",
                packageName = "com.example.app",
                pid = "1111",
                exceptionType = "java.lang.NullPointerException",
                exceptionMessage = "First crash"
            )
            val crash2 = buildLogcatCrash(
                timestamp = "01-15 10:35:00.000",
                packageName = "com.example.app",
                pid = "2222",
                exceptionType = "java.lang.IllegalStateException",
                exceptionMessage = "Second crash"
            )
            val logcat = "$crash1\n$crash2"

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(2)
            assertThat(result.crashes[0].cause).contains("NullPointerException")
            assertThat(result.crashes[1].cause).contains("IllegalStateException")
        }

        @Test
        fun `returns empty list when no crash in logcat`() {
            val logcat = """
                01-15 10:30:00.000 D/MyApp(12345): Normal log message
                01-15 10:30:01.000 I/MyApp(12345): Another normal message
                01-15 10:30:02.000 W/MyApp(12345): Warning but not a crash
            """.trimIndent()

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).isEmpty()
        }

        @Test
        fun `returns empty list for empty input`() {
            val result = LogcatReader.findCrashes("")

            assertThat(result.crashes).isEmpty()
        }

        @Test
        fun `extracts cause from line after Process`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.myapp",
                pid = "9999",
                exceptionType = "kotlin.UninitializedPropertyAccessException",
                exceptionMessage = "lateinit property viewModel has not been initialized"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].cause).contains("UninitializedPropertyAccessException")
            assertThat(result.crashes[0].cause).contains("lateinit property viewModel")
        }

        @Test
        fun `handles real logcat format with metadata`() {
            // Real logcat output includes log level, tag, and PID in format: "MM-dd HH:mm:ss.SSS E/AndroidRuntime(PID):"
            val logcat = """
                ${recentTimestamp()} E/AndroidRuntime(28715): FATAL EXCEPTION: main
                ${recentTimestamp()} E/AndroidRuntime(28715): Process: com.example.app, PID: 28715
                ${recentTimestamp()} E/AndroidRuntime(28715): java.lang.RuntimeException: Unable to start activity
                ${recentTimestamp()} E/AndroidRuntime(28715):     at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3449)
                ${recentTimestamp()} E/AndroidRuntime(28715):     at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3601)
                ${recentTimestamp()} E/AndroidRuntime(28715): Caused by: java.lang.NullPointerException
                ${recentTimestamp()} E/AndroidRuntime(28715):     at com.example.app.MainActivity.onCreate(MainActivity.kt:25)
            """.trimIndent()

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].stackTrace).contains("ActivityThread.performLaunchActivity")
            assertThat(result.crashes[0].stackTrace).contains("Caused by: java.lang.NullPointerException")
        }

        @Test
        fun `captures full stack trace including nested exceptions`() {
            val logcat = buildLogcatCrashWithCausedBy(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                primaryException = "java.lang.RuntimeException: Failed to initialize",
                causedBy = "java.io.FileNotFoundException: config.json not found"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].stackTrace).contains("RuntimeException")
            assertThat(result.crashes[0].stackTrace).contains("Caused by")
            assertThat(result.crashes[0].stackTrace).contains("FileNotFoundException")
        }

        @Test
        fun `parses OutOfMemoryError crash`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "12345",
                exceptionType = "java.lang.OutOfMemoryError",
                exceptionMessage = "Failed to allocate a 52428816 byte allocation with 25165824 free bytes"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].cause).contains("OutOfMemoryError")
        }

        @Test
        fun `parses ANR-style crash in crash buffer`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "5555",
                exceptionType = "android.os.TransactionTooLargeException",
                exceptionMessage = "data parcel size 1048576 bytes"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].cause).contains("TransactionTooLargeException")
        }
    }

    @Nested
    inner class GetLastCrash {

        @Test
        fun `returns most recent crash when multiple exist`() {
            val oldTimestamp = "01-01 10:00:00.000"
            val newTimestamp = recentTimestamp()

            val crash1 = buildLogcatCrash(
                timestamp = oldTimestamp,
                packageName = "com.example.app",
                pid = "1111",
                exceptionType = "java.lang.OldException",
                exceptionMessage = "Old crash"
            )
            val crash2 = buildLogcatCrash(
                timestamp = newTimestamp,
                packageName = "com.example.app",
                pid = "2222",
                exceptionType = "java.lang.NewException",
                exceptionMessage = "Recent crash"
            )
            val logcat = "$crash1\n$crash2"

            val result = LogcatReader.findCrashes(logcat).getLastCrash(timeSpan = null)

            assertThat(result).isNotNull()
            assertThat(result!!.cause).contains("NewException")
        }

        @Test
        fun `returns null when no crashes exist`() {
            val result = LogcatReader.findCrashes("").getLastCrash()

            assertThat(result).isNull()
        }

        @Test
        fun `filters crashes by time span`() {
            // Old crash from January 1st - should be filtered out
            val oldCrash = buildLogcatCrash(
                timestamp = "01-01 10:00:00.000",
                packageName = "com.example.app",
                pid = "1111",
                exceptionType = "java.lang.OldException",
                exceptionMessage = "Old crash"
            )

            val result = LogcatReader.findCrashes(oldCrash)

            // Default timespan is 5 minutes, old crash should be filtered
            assertThat(result.getLastCrash()).isNull()
            // Without filter, should find it
            assertThat(result.getLastCrash(timeSpan = null)).isNotNull()
        }

        @Test
        fun `returns crash within time span`() {
            val recentCrash = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "1111",
                exceptionType = "java.lang.RecentException",
                exceptionMessage = "Recent crash"
            )

            val result = LogcatReader.findCrashes(recentCrash)

            // Should find recent crash with default 5 minute window
            assertThat(result.getLastCrash()).isNotNull()
            assertThat(result.getLastCrash()!!.cause).contains("RecentException")
        }

        @Test
        fun `custom time span works correctly`() {
            // Create crash from 2 hours ago
            val twoHoursAgo = Calendar.getInstance().apply {
                add(Calendar.HOUR, -2)
            }
            val timestamp = String.format(
                "%02d-%02d %02d:%02d:%02d.000",
                twoHoursAgo.get(Calendar.MONTH) + 1,
                twoHoursAgo.get(Calendar.DAY_OF_MONTH),
                twoHoursAgo.get(Calendar.HOUR_OF_DAY),
                twoHoursAgo.get(Calendar.MINUTE),
                twoHoursAgo.get(Calendar.SECOND)
            )

            val crash = buildLogcatCrash(
                timestamp = timestamp,
                packageName = "com.example.app",
                pid = "1111",
                exceptionType = "java.lang.TestException",
                exceptionMessage = "Test"
            )

            val result = LogcatReader.findCrashes(crash)

            // 1 hour span should NOT find it
            val oneHourSpan = LogcatCrashReport.TimeAgo(1, TimeUnit.HOURS)
            assertThat(result.getLastCrash(oneHourSpan)).isNull()

            // 3 hour span SHOULD find it
            val threeHourSpan = LogcatCrashReport.TimeAgo(3, TimeUnit.HOURS)
            assertThat(result.getLastCrash(threeHourSpan)).isNotNull()
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `handles logcat with only FATAL EXCEPTION header, no stack trace`() {
            val logcat = "${recentTimestamp()} E/AndroidRuntime(12345): FATAL EXCEPTION: main"

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].cause).isEmpty()
        }

        @Test
        fun `handles mixed log levels in crash output`() {
            val logcat = """
                ${recentTimestamp()} D/MyApp(12345): Debug before crash
                ${recentTimestamp()} E/AndroidRuntime(12345): FATAL EXCEPTION: main
                ${recentTimestamp()} E/AndroidRuntime(12345): Process: com.example.app, PID: 12345
                ${recentTimestamp()} E/AndroidRuntime(12345): java.lang.RuntimeException: Test
                ${recentTimestamp()} I/MyApp(12345): Info log interleaved
                ${recentTimestamp()} E/AndroidRuntime(12345):     at com.example.Test.method(Test.kt:10)
            """.trimIndent()

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].stackTrace).contains("Test.method")
        }

        @Test
        fun `handles package name with dots and hyphens`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.my-app.alpha",
                pid = "12345",
                exceptionType = "java.lang.Exception",
                exceptionMessage = "Test"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
        }

        @Test
        fun `handles very long stack traces`() {
            val stackTraceLines = (1..100).map {
                "at com.example.deep.Class$it.method(Class$it.kt:$it)"
            }

            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "12345",
                exceptionType = "java.lang.StackOverflowError",
                exceptionMessage = "Stack overflow",
                stackTrace = stackTraceLines
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].stackTrace).contains("Class50.method")
            assertThat(result.crashes[0].stackTrace).contains("Class100.method")
        }

        @Test
        fun `handles unicode in exception message`() {
            val logcat = buildLogcatCrash(
                timestamp = recentTimestamp(),
                packageName = "com.example.app",
                pid = "12345",
                exceptionType = "java.lang.IllegalArgumentException",
                exceptionMessage = "Invalid input: こんにちは 🚀"
            )

            val result = LogcatReader.findCrashes(logcat)

            assertThat(result.crashes).hasSize(1)
            assertThat(result.crashes[0].stackTrace).contains("こんにちは")
        }
    }

    @Nested
    inner class NativeCrashes {

        @Test
        fun `detects native SIGSEGV crash with package and signal`() {
            val result = LogcatReader.findCrashes(buildNativeCrash(recentTimestamp(), "com.bww.reactive.uat"))

            assertThat(result.crashes).hasSize(1)
            val crash = result.crashes[0]
            assertThat(crash.packageId).isEqualTo("com.bww.reactive.uat")
            assertThat(crash.cause).contains("SIGSEGV")
            assertThat(crash.stackTrace).contains("libreactnative.so")
        }

        @Test
        fun `does not detect a native crash in ordinary logcat`() {
            val logcat = """
                07-14 10:30:00.000 D/MyApp(12345): Normal log message
                07-14 10:30:01.000 I/MyApp(12345): Another normal message
            """.trimIndent()

            assertThat(LogcatReader.findCrashes(logcat).crashes).isEmpty()
        }
    }

    @Test
    fun `extracts package id from a JVM crash`() {
        val logcat = buildLogcatCrash(
            timestamp = recentTimestamp(),
            packageName = "com.example.app",
            pid = "28715",
            exceptionType = "java.lang.NullPointerException",
            exceptionMessage = "boom"
        )

        assertThat(LogcatReader.findCrashes(logcat).crashes.single().packageId).isEqualTo("com.example.app")
    }

    @Test
    fun `getLastCrash filters by package id`() {
        val logcat = buildNativeCrash(recentTimestamp(), "com.bww.reactive.uat") + "\n" +
            buildNativeCrash(recentTimestamp(), "com.other.app")

        val report = LogcatReader.findCrashes(logcat)

        assertThat(report.getLastCrash("com.bww.reactive.uat", timeSpan = null)?.packageId)
            .isEqualTo("com.bww.reactive.uat")
        assertThat(report.getLastCrash("com.nonexistent", timeSpan = null)).isNull()
    }

    // Test helpers

    private fun buildNativeCrash(timestamp: String, packageId: String): String {
        return """
            $timestamp F/libc    ( 4963): Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x744b453f90b400f0 in tid 5093 (mqt_v_js), pid 4963 (com.bww.reactiv)
            $timestamp F/DEBUG   ( 5100): *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            $timestamp F/DEBUG   ( 5100): pid: 4963, tid: 5093, name: mqt_v_js  >>> $packageId <<<
            $timestamp F/DEBUG   ( 5100): signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x744b453f90b400f0
            $timestamp F/DEBUG   ( 5100): backtrace:
            $timestamp F/DEBUG   ( 5100):       #00 pc 00000000004f4d44  libreactnative.so (facebook::react::ShadowTreeRegistry::enumerate)
        """.trimIndent()
    }

    private fun recentTimestamp(): String {
        val now = Calendar.getInstance()
        return String.format(
            "%02d-%02d %02d:%02d:%02d.000",
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND)
        )
    }

    private fun buildLogcatCrash(
        timestamp: String,
        packageName: String,
        pid: String,
        exceptionType: String,
        exceptionMessage: String,
        stackTrace: List<String> = listOf(
            "at com.example.DefaultClass.method(DefaultClass.kt:10)"
        )
    ): String {
        val stackTraceStr = stackTrace.joinToString("\n") { "    $it" }
        return """
            $timestamp E/AndroidRuntime($pid): FATAL EXCEPTION: main
            $timestamp E/AndroidRuntime($pid): Process: $packageName, PID: $pid
            $timestamp E/AndroidRuntime($pid): $exceptionType: $exceptionMessage
            $stackTraceStr
        """.trimIndent()
    }

    private fun buildLogcatCrashWithCausedBy(
        timestamp: String,
        packageName: String,
        primaryException: String,
        causedBy: String
    ): String {
        return """
            $timestamp E/AndroidRuntime(12345): FATAL EXCEPTION: main
            $timestamp E/AndroidRuntime(12345): Process: $packageName, PID: 12345
            $timestamp E/AndroidRuntime(12345): $primaryException
            $timestamp E/AndroidRuntime(12345):     at com.example.Outer.method(Outer.kt:10)
            $timestamp E/AndroidRuntime(12345): Caused by: $causedBy
            $timestamp E/AndroidRuntime(12345):     at com.example.Inner.method(Inner.kt:20)
        """.trimIndent()
    }

    // ========== ANR Tests ==========

    @Nested
    inner class FindANRs {

        @Test
        fun `parses real ActivityManager log dump with ANR`() {
            // Real ActivityManager log dump from a device
            val logcat = """
                12-10 21:31:01.059 D/ActivityManager(  539): freezing 1917 android.process.acore
                12-10 21:31:04.234 D/ActivityManager(  539): freezing 1868 com.google.android.youtube
                12-10 21:31:13.810 D/ActivityManager(  539): freezing 6250 com.google.android.gms.unstable
                12-10 21:31:54.594 D/ActivityManager(  539): created ANR temporary file:/data/anr/temp_anr_8447485492039548511.txt
                12-10 21:31:54.594 I/ActivityManager(  539): Collecting stacks for pid 8662 into temporary file temp_anr_8447485492039548511.txt
                12-10 21:31:57.980 I/ActivityManager(  539): Done dumping
                12-10 21:31:57.981 E/ActivityManager(  539): ANR in br.com.quintoandar.inquilinos.forno
                12-10 21:31:57.981 E/ActivityManager(  539): PID: 8662
                12-10 21:31:57.981 E/ActivityManager(  539): Reason: App requested: Buffer processing hung up due to stuck fence. Indicates GPU hang
                12-10 21:31:57.981 E/ActivityManager(  539): ErrorId: ed602d3d-3fef-4f64-9a0f-c011beb9fef1
                12-10 21:31:57.981 E/ActivityManager(  539): Frozen: false
                12-10 21:31:57.981 E/ActivityManager(  539): Load: 2.91 / 2.1 / 0.87
                12-10 21:31:57.981 E/ActivityManager(  539): ----- Output from /proc/pressure/memory -----
                12-10 21:31:57.981 E/ActivityManager(  539): some avg10=0.00 avg60=0.25 avg300=0.25 total=1442007
                12-10 21:31:57.981 E/ActivityManager(  539): full avg10=0.00 avg60=0.05 avg300=0.04 total=451453
                12-10 21:31:57.981 E/ActivityManager(  539): ----- End output from /proc/pressure/memory -----
                12-10 21:31:57.981 E/ActivityManager(  539): CPU usage from 143904ms to -5ms ago (2025-12-05 17:20:48.510 to 2025-12-10 21:31:54.591):
                12-10 21:31:57.981 E/ActivityManager(  539):   17% 539/system_server: 10% user + 7% kernel / faults: 496600 minor 468 major
                12-10 21:31:57.981 E/ActivityManager(  539): 40% TOTAL: 17% user + 12% kernel + 1.6% iowait + 8.8% irq + 0.7% softirq
                12-10 21:31:57.987 D/ActivityManager(  539): Completed ANR of br.com.quintoandar.inquilinos.forno in 3408ms, latency 1ms
                12-10 21:32:03.585 D/ActivityManager(  539): freezing 891 com.google.android.permissioncontroller
                12-10 21:32:21.268 I/ActivityManager(  539): Broadcast completed: result=0
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.anrs).hasSize(1)
            val anr = result.anrs[0]

            // Core parsing
            assertThat(anr.packageId).isEqualTo("br.com.quintoandar.inquilinos.forno")
            assertThat(anr.pid).isEqualTo(8662)
            assertThat(anr.reason).isEqualTo("App requested: Buffer processing hung up due to stuck fence. Indicates GPU hang")
            assertThat(anr.friendlyMessage).isEqualTo("ANR: App requested: Buffer processing hung up due to stuck fence. Indicates GPU hang")

            // Raw log captures content between start and end markers
            assertThat(anr.rawLog).contains("ANR in br.com.quintoandar.inquilinos.forno")
            assertThat(anr.rawLog).contains("PID: 8662")
            assertThat(anr.rawLog).contains("Load: 2.91")
            assertThat(anr.rawLog).contains("CPU usage from")
            assertThat(anr.rawLog).contains("Completed ANR of br.com.quintoandar.inquilinos.forno")

            // Excludes logs outside ANR boundaries
            assertThat(anr.rawLog).doesNotContain("freezing 1917 android.process.acore")
            assertThat(anr.rawLog).doesNotContain("Done dumping")
            assertThat(anr.rawLog).doesNotContain("freezing 891 com.google.android.permissioncontroller")
            assertThat(anr.rawLog).doesNotContain("Broadcast completed")
        }

        @Test
        fun `returns empty list when no ANR in logcat`() {
            val result = LogcatReader.findANRs("")
            assertThat(result.anrs).isEmpty()
        }

        @Test
        fun `friendly message falls back to package when no reason`() {
            val logcat = """
                12-10 21:31:57.981 E/ActivityManager(  539): ANR in com.example.app
                12-10 21:31:57.981 E/ActivityManager(  539): PID: 12345
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.anrs).hasSize(1)
            assertThat(result.anrs[0].friendlyMessage).isEqualTo("ANR in com.example.app")
        }

        @Test
        fun `handles ANR without completion marker`() {
            val logcat = """
                12-10 21:31:57.981 E/ActivityManager(  539): ANR in com.example.app
                12-10 21:31:57.981 E/ActivityManager(  539): PID: 12345
                12-10 21:31:57.981 E/ActivityManager(  539): Reason: Test
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.anrs).hasSize(1)
            assertThat(result.anrs[0].packageId).isEqualTo("com.example.app")
        }

        @Test
        fun `handles multiple ANRs with proper boundaries`() {
            val logcat = """
                12-10 21:30:00.000 E/ActivityManager(  539): ANR in com.example.app1
                12-10 21:30:00.000 E/ActivityManager(  539): PID: 1111
                12-10 21:30:00.000 E/ActivityManager(  539): Reason: First ANR
                12-10 21:30:00.100 D/ActivityManager(  539): Completed ANR of com.example.app1 in 100ms
                12-10 21:31:00.000 E/ActivityManager(  539): ANR in com.example.app2
                12-10 21:31:00.000 E/ActivityManager(  539): PID: 2222
                12-10 21:31:00.000 E/ActivityManager(  539): Reason: Second ANR
                12-10 21:31:00.200 D/ActivityManager(  539): Completed ANR of com.example.app2 in 200ms
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.anrs).hasSize(2)
            assertThat(result.anrs[0].packageId).isEqualTo("com.example.app1")
            assertThat(result.anrs[1].packageId).isEqualTo("com.example.app2")

            // Boundaries are respected
            assertThat(result.anrs[0].rawLog).doesNotContain("Second ANR")
            assertThat(result.anrs[1].rawLog).doesNotContain("First ANR")
        }

        @Test
        fun `completion marker for different package does not end current ANR`() {
            val logcat = """
                12-10 21:30:00.000 E/ActivityManager(  539): ANR in com.example.app1
                12-10 21:30:00.000 E/ActivityManager(  539): PID: 1111
                12-10 21:30:00.000 E/ActivityManager(  539): Reason: First ANR
                12-10 21:30:00.100 D/ActivityManager(  539): Completed ANR of com.example.other in 100ms
                12-10 21:30:00.200 E/ActivityManager(  539): More logs for app1
                12-10 21:30:00.300 D/ActivityManager(  539): Completed ANR of com.example.app1 in 200ms
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.anrs).hasSize(1)
            assertThat(result.anrs[0].rawLog).contains("More logs for app1")
            assertThat(result.anrs[0].rawLog).contains("Completed ANR of com.example.app1")
        }
    }

    @Nested
    inner class GetLastANR {

        @Test
        fun `returns most recent ANR`() {
            val logcat = """
                01-01 10:00:00.000 E/ActivityManager(  539): ANR in com.example.old
                01-01 10:00:00.000 E/ActivityManager(  539): PID: 1111
                01-01 10:00:00.100 D/ActivityManager(  539): Completed ANR of com.example.old in 100ms
                12-10 21:31:57.981 E/ActivityManager(  539): ANR in com.example.new
                12-10 21:31:57.981 E/ActivityManager(  539): PID: 2222
                12-10 21:31:57.987 D/ActivityManager(  539): Completed ANR of com.example.new in 100ms
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat).getLastANR()

            assertThat(result).isNotNull()
            assertThat(result!!.packageId).isEqualTo("com.example.new")
        }

        @Test
        fun `filters by package id`() {
            val logcat = """
                12-10 21:30:00.000 E/ActivityManager(  539): ANR in com.example.app1
                12-10 21:30:00.000 E/ActivityManager(  539): PID: 1111
                12-10 21:30:00.100 D/ActivityManager(  539): Completed ANR of com.example.app1 in 100ms
                12-10 21:31:00.000 E/ActivityManager(  539): ANR in com.example.app2
                12-10 21:31:00.000 E/ActivityManager(  539): PID: 2222
                12-10 21:31:00.100 D/ActivityManager(  539): Completed ANR of com.example.app2 in 100ms
            """.trimIndent()

            val result = LogcatReader.findANRs(logcat)

            assertThat(result.getLastANR("com.example.app1")?.packageId).isEqualTo("com.example.app1")
            assertThat(result.getLastANR("com.example.app2")?.packageId).isEqualTo("com.example.app2")
            assertThat(result.getLastANR("com.example.nonexistent")).isNull()
        }
    }
}
