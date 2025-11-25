# --include-logs Implementation Progress

## Status: 40% Complete (4/10 steps)

---

## ✅ Completed Steps

### Step 1: CLI Flags ✓
**Commit:** `aa383543`
**Files Changed:**
- `maestro-cli/src/main/java/maestro/cli/command/TestCommand.kt`

**What was added:**
```kotlin
@Option(names = ["--include-logs"])
private var includeLogs: String? = null

@Option(names = ["--log-buffer-size"])
private var logBufferSize: Int = 5000
```

**Usage:**
```bash
# Include all logs
maestro test --include-logs flow.yaml

# Include specific levels
maestro test --include-logs=ERROR,WARN flow.yaml

# Custom buffer size
maestro test --include-logs --log-buffer-size=10000 flow.yaml
```

---

### Step 2: Data Models ✓
**Commit:** `36b549f6`
**Files Changed:**
- `maestro-client/src/main/java/maestro/LogEntry.kt` (NEW)
- `maestro-cli/src/main/java/maestro/cli/model/TestExecutionSummary.kt`

**What was added:**
```kotlin
// LogEntry.kt
data class LogEntry(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String
)

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT
}

// TestExecutionSummary.kt
data class FlowResult(
    // ... existing fields ...
    val logs: List<LogEntry> = emptyList(),
    val commandLogs: Map<String, List<LogEntry>> = emptyMap()
)
```

---

### Step 3: Driver Interface ✓
**Commit:** `a1a9971a`
**Files Changed:**
- `maestro-client/src/main/java/maestro/Driver.kt`

**What was added:**
```kotlin
fun startLogCapture(
    packageName: String? = null,
    tag: String? = null,
    minLevel: String = "INFO",
    bufferSize: Int? = null
) = Unit

fun stopLogCapture(): List<LogEntry> = emptyList()
fun getLogStream(): List<LogEntry> = emptyList()
```

**Note:** Default implementations return empty. AndroidDriver will override later.

---

### Step 4: LogCapture Utility ✓
**Commits:** `42ec3aff`, `3ec9fe09`
**Files Changed:**
- `maestro-cli/src/main/java/maestro/cli/util/LogCapture.kt` (NEW)
- `maestro-cli/src/test/kotlin/maestro/cli/util/LogCaptureTest.kt` (NEW)

**What was added:**
- Simple `adb logcat` based log capture
- Background thread to continuously read logs
- Configurable buffer size (default: 5000)
- Parses logcat format: `MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE`
- Comprehensive unit tests

**Example Usage:**
```kotlin
val logCapture = LogCapture(deviceId = "emulator-5554", bufferSize = 5000)
logCapture.start()
// ... run test ...
val logs = logCapture.stop()
```

---

## 🚧 Remaining Steps (6 of 10)

### Step 5: Integrate Log Capture in TestSuiteInteractor
**Status:** IN PROGRESS
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt`

**Changes needed:**

1. **Add constructor parameters:**
```kotlin
class TestSuiteInteractor(
    // ... existing params ...
    private val logLevels: Set<LogLevel>? = null,  // NEW
    private val logBufferSize: Int = 5000,  // NEW
)
```

2. **Start log capture in runFlow():**
```kotlin
private suspend fun runFlow(...): Pair<TestExecutionSummary.FlowResult, FlowAIOutput> {
    val shouldCaptureLogs = logLevels != null
    val logCapture = if (shouldCaptureLogs) {
        LogCapture(deviceId = device?.instanceId, bufferSize = logBufferSize).apply {
            start()
        }
    } else null

    // Track command start times for correlation
    val commandLogs = mutableMapOf<String, MutableList<LogEntry>>()
    var currentCommandStartTime = System.currentTimeMillis()

    // ... existing code ...
```

3. **Correlate logs with commands in Orchestra callbacks:**
```kotlin
val orchestra = Orchestra(
    maestro = maestro,
    onCommandStart = { _, command ->
        logger.info("${shardPrefix}${command.description()} RUNNING")
        currentCommandStartTime = System.currentTimeMillis()
        debugOutput.commands[command] = CommandDebugMetadata(...)
    },
    onCommandComplete = { _, command ->
        logger.info("${shardPrefix}${command.description()} COMPLETED")

        // Capture logs for this command
        if (shouldCaptureLogs) {
            val logs = logCapture!!.getBufferedLogs()
                .filter { log ->
                    parseLogTimestamp(log.timestamp) >= currentCommandStartTime &&
                    logLevels!!.contains(log.level)
                }
            commandLogs[command.description()] = logs.toMutableList()
        }

        // ... existing code ...
    },
    // ... other callbacks ...
)
```

4. **Stop capture and save logs:**
```kotlin
    // After orchestra.runFlow()
    val allLogs = if (shouldCaptureLogs) {
        logCapture!!.stop().filter { logLevels!!.contains(it.level) }
    } else if (flowStatus == FlowStatus.ERROR) {
        // Auto-capture error logs for failed tests
        logCapture?.stop()?.filter {
            it.level == LogLevel.ERROR || it.level == LogLevel.ASSERT
        } ?: emptyList()
    } else {
        emptyList()
    }

    return Pair(
        first = TestExecutionSummary.FlowResult(
            name = flowName,
            fileName = flowFile.nameWithoutExtension,
            status = flowStatus,
            failure = ...,
            duration = flowDuration,
            logs = allLogs,
            commandLogs = commandLogs
        ),
        second = aiOutput
    )
}
```

**Helper function needed:**
```kotlin
private fun parseLogTimestamp(timestamp: String): Long {
    // Parse "MM-DD HH:MM:SS.mmm" to milliseconds
    // Implementation needed
}
```

**Estimated effort:** 2-3 hours

---

### Step 6: Add JSON Export in TestDebugReporter
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/report/TestDebugReporter.kt`

**Changes needed:**

1. **Add saveLogs() method:**
```kotlin
fun saveLogs(
    flowName: String,
    allLogs: List<LogEntry>,
    commandLogs: Map<String, List<LogEntry>>,
    shardIndex: Int?,
    path: Path
) {
    if (allLogs.isEmpty()) return

    val shardSuffix = shardIndex?.let { "-shard-${it + 1}" } ?: ""
    val fileName = "logs-($flowName)$shardSuffix.json"

    val json = buildJsonObject {
        put("flowName", flowName)
        put("totalEntries", allLogs.size)
        put("capturedAt", System.currentTimeMillis())

        // All logs
        put("logs", JsonArray(allLogs.map { log ->
            buildJsonObject {
                put("timestamp", log.timestamp)
                put("level", log.level.toString())
                put("tag", log.tag)
                put("message", log.message)
                put("pid", log.pid)
                put("tid", log.tid)
            }
        }))

        // Logs grouped by command
        put("commandLogs", buildJsonObject {
            commandLogs.forEach { (command, logs) ->
                put(command, JsonArray(logs.map { log ->
                    buildJsonObject {
                        put("timestamp", log.timestamp)
                        put("level", log.level.toString())
                        put("tag", log.tag)
                        put("message", log.message)
                    }
                }))
            }
        })
    }

    val file = path.resolve(fileName).toFile()
    file.writeText(json.toString())
    logger.info("Saved ${allLogs.size} log entries to $fileName")
}
```

2. **Call from saveFlow():**
```kotlin
fun saveFlow(
    flowName: String,
    debugOutput: FlowDebugOutput,
    shardIndex: Int?,
    path: Path,
) {
    // ... existing code ...

    // Save logs if present
    val flowResult = // get from debugOutput
    saveLogs(flowName, flowResult.logs, flowResult.commandLogs, shardIndex, path)
}
```

**Estimated effort:** 1 hour

---

### Step 7: Update HTML Reporter with Console Logs Tab
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/report/HtmlTestSuiteReporter.kt`

**Changes needed:**

1. **Add constructor parameter:**
```kotlin
class HtmlTestSuiteReporter(
    private val includeLogs: Boolean = false,
    private val logLevels: Set<LogLevel>? = null
) : TestSuiteReporter
```

2. **Add Console Logs tab button:**
```kotlin
// In buildHtmlReport(), add after Network Requests tab
if (summary.suites.any { suite -> suite.flows.any { it.logs.isNotEmpty() } }) {
    li(classes = "nav-item") {
        attributes["role"] = "presentation"
        button(classes = "nav-link") {
            attributes["data-bs-toggle"] = "tab"
            attributes["data-bs-target"] = "#console-logs"
            +"📋 Console Logs"
        }
    }
}
```

3. **Add Console Logs tab content:**
```kotlin
// Add after Network Requests tab content
if (summary.suites.any { suite -> suite.flows.any { it.logs.isNotEmpty() } }) {
    div(classes = "tab-pane fade") {
        id = "console-logs"
        attributes["role"] = "tabpanel"

        div(classes = "card") {
            div(classes = "card-body") {
                h2(classes = "text-center mb-4") { +"📋 Console Logs" }

                // Log level filter buttons
                div(classes = "log-filters mb-3") {
                    button(classes = "log-filter-btn active") {
                        attributes["onclick"] = "filterLogs('all')"
                        +"All"
                    }
                    LogLevel.values().forEach { level ->
                        button(classes = "log-filter-btn") {
                            attributes["onclick"] = "filterLogs('${level.name.lowercase()}')"
                            +level.name
                        }
                    }
                    // Search box
                    input(classes = "form-control log-search") {
                        attributes["type"] = "text"
                        attributes["placeholder"] = "Search logs..."
                        attributes["onkeyup"] = "searchLogs(this.value)"
                    }
                }

                suite.flows.forEach { flow ->
                    if (flow.logs.isNotEmpty()) {
                        h4(classes = "mt-4") {
                            +flow.name
                            if (flow.status == FlowStatus.ERROR) {
                                span(classes = "badge bg-danger ms-2") { +"FAILED" }
                            }
                        }
                        p(classes = "text-muted") {
                            +"${flow.logs.size} log entries"
                        }

                        // Command-correlated logs
                        if (flow.commandLogs.isNotEmpty()) {
                            flow.commandLogs.forEach { (commandDesc, logs) ->
                                div(classes = "log-command-section") {
                                    h6(classes = "log-command-title") {
                                        +"▸ $commandDesc"
                                        span(classes = "badge bg-secondary ms-2") {
                                            +"${logs.size} logs"
                                        }
                                    }
                                    div(classes = "log-timeline") {
                                        logs.forEach { log ->
                                            renderLogEntry(log)
                                        }
                                    }
                                }
                            }
                        } else {
                            // All logs (not correlated)
                            div(classes = "log-timeline") {
                                flow.logs.forEach { log ->
                                    renderLogEntry(log)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

4. **Add helper method:**
```kotlin
private fun DIV.renderLogEntry(log: LogEntry) {
    div(classes = "log-entry log-${log.level.name.lowercase()}") {
        attributes["data-level"] = log.level.name.lowercase()
        attributes["data-content"] = "${log.timestamp} ${log.tag} ${log.message}".lowercase()

        span(classes = "log-timestamp") { +log.timestamp }
        span(classes = "log-level") { +log.level.name }
        span(classes = "log-tag") { +log.tag }
        span(classes = "log-message") { +log.message }
    }
}
```

**Estimated effort:** 2 hours

---

### Step 8: Add CSS Styles and Vanilla JS
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/report/HtmlTestSuiteReporter.kt`

**Changes needed:**

1. **Add CSS in head section:**
```kotlin
style {
    unsafe {
        +"""
        /* Log Timeline Styles */
        .log-timeline {
            font-family: 'SF Mono', 'Monaco', 'Courier New', monospace;
            font-size: 13px;
            background-color: #1e1e1e;
            color: #d4d4d4;
            padding: 15px;
            border-radius: 6px;
            max-height: 500px;
            overflow-y: auto;
            margin-bottom: 15px;
        }

        .log-entry {
            padding: 4px 0;
            border-left: 3px solid transparent;
            padding-left: 10px;
            margin-bottom: 2px;
            display: grid;
            grid-template-columns: 100px 70px 180px 1fr;
            gap: 12px;
            line-height: 1.5;
        }

        .log-entry:hover {
            background-color: #2d2d30;
        }

        .log-entry.hidden {
            display: none;
        }

        /* Log Level Colors */
        .log-verbose .log-level { background-color: #858585; color: #fff; }
        .log-debug .log-level { background-color: #569cd6; color: #fff; }
        .log-info .log-level { background-color: #4ec9b0; color: #000; }
        .log-warn .log-level { background-color: #dcdcaa; color: #000; }
        .log-error .log-level { background-color: #f48771; color: #000; }
        .log-assert .log-level { background-color: #d16969; color: #fff; }

        /* Command sections */
        .log-command-section {
            margin-bottom: 20px;
            border-left: 3px solid #007bff;
            padding-left: 10px;
        }

        .log-command-title {
            color: #007bff;
            font-size: 14px;
            font-weight: 600;
            margin-bottom: 10px;
            cursor: pointer;
        }

        /* Filters */
        .log-filters {
            display: flex;
            gap: 10px;
            align-items: center;
        }

        .log-filter-btn {
            padding: 6px 14px;
            border: 1px solid #ccc;
            border-radius: 4px;
            background: white;
            cursor: pointer;
            font-size: 13px;
        }

        .log-filter-btn.active {
            background: #007bff;
            color: white;
            border-color: #007bff;
        }

        .log-search {
            max-width: 300px;
            margin-left: auto;
        }
        """.trimIndent()
    }
}
```

2. **Add JavaScript before </body>:**
```kotlin
script {
    unsafe {
        +"""
        // Filter logs by level
        function filterLogs(level) {
            document.querySelectorAll('.log-filter-btn').forEach(btn => {
                btn.classList.remove('active');
                if (btn.textContent.trim().toLowerCase() === level ||
                    (level === 'all' && btn.textContent.trim() === 'All')) {
                    btn.classList.add('active');
                }
            });

            document.querySelectorAll('.log-entry').forEach(entry => {
                if (level === 'all') {
                    entry.classList.remove('hidden');
                } else {
                    entry.classList.toggle('hidden',
                        entry.getAttribute('data-level') !== level);
                }
            });
        }

        // Search logs
        function searchLogs(query) {
            const searchTerm = query.toLowerCase();
            document.querySelectorAll('.log-entry').forEach(entry => {
                const content = entry.getAttribute('data-content');
                entry.classList.toggle('hidden',
                    query !== '' && !content.includes(searchTerm));
            });
        }

        // Collapse/expand command sections
        document.querySelectorAll('.log-command-title').forEach(title => {
            title.addEventListener('click', function() {
                const timeline = this.nextElementSibling;
                timeline.style.display =
                    timeline.style.display === 'none' ? 'block' : 'none';
                this.textContent = this.textContent.replace(/^[▸▾]/,
                    timeline.style.display === 'none' ? '▸' : '▾');
            });
        });
        """.trimIndent()
    }
}
```

**Estimated effort:** 1 hour

---

### Step 9: Update ReporterFactory
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/report/ReporterFactory.kt`

**Changes needed:**

```kotlin
fun buildReporter(
    format: ReportFormat,
    testSuiteName: String?,
    includeLogs: Boolean = false,  // NEW
    logLevels: Set<LogLevel>? = null  // NEW
): TestSuiteReporter {
    return when (format) {
        ReportFormat.HTML -> HtmlTestSuiteReporter(includeLogs, logLevels)
        ReportFormat.JUNIT -> JUnitTestSuiteReporter(testSuiteName)
        ReportFormat.NOOP -> TestSuiteReporter.NOOP
    }
}
```

**Estimated effort:** 15 minutes

---

### Step 10: Wire Everything Together in TestCommand
**Files to modify:**
- `maestro-cli/src/main/java/maestro/cli/command/TestCommand.kt`

**Changes needed:**

1. **Parse log levels in call():**
```kotlin
override fun call(): Int {
    // ... existing code ...

    // Parse log configuration
    val logLevels = parseLogLevels(includeLogs)
    val shouldCaptureLogs = logLevels != null

    if (shouldCaptureLogs) {
        logger.info("Log capture enabled with levels: ${logLevels?.joinToString()}")
        logger.info("Log buffer size: $logBufferSize")
    }

    // ... continue with execution ...
}

private fun parseLogLevels(includeLogsValue: String?): Set<LogLevel>? {
    return when {
        includeLogsValue == null -> null  // Not enabled
        includeLogsValue.isEmpty() -> LogLevel.values().toSet()  // All levels
        else -> {
            includeLogsValue.split(",")
                .map { it.trim().uppercase() }
                .mapNotNull {
                    try { LogLevel.valueOf(it) }
                    catch (e: Exception) {
                        PrintUtils.warn("Unknown log level: $it")
                        null
                    }
                }
                .toSet()
        }
    }
}
```

2. **Pass to TestSuiteInteractor:**
```kotlin
private suspend fun runMultipleFlows(
    maestro: Maestro,
    device: Device?,
    chunkPlans: List<ExecutionPlan>,
    shardIndex: Int,
    debugOutputPath: Path,
    testOutputDir: Path?,
    logLevels: Set<LogLevel>?,  // NEW
    logBufferSize: Int  // NEW
): Triple<Int?, Int?, TestExecutionSummary> {
    // ... existing code ...

    val suiteResult = TestSuiteInteractor(
        maestro = maestro,
        device = device,
        shardIndex = if (chunkPlans.size == 1) null else shardIndex,
        reporter = ReporterFactory.buildReporter(
            format,
            testSuiteName,
            includeLogs = logLevels != null,
            logLevels = logLevels
        ),
        logLevels = logLevels,  // NEW
        logBufferSize = logBufferSize  // NEW
    ).runTestSuite(...)

    // ... rest of code ...
}
```

3. **Update runShardSuite():**
```kotlin
private fun runShardSuite(...): Triple<Int?, Int?, TestExecutionSummary?> {
    // ... existing code ...

    return MaestroSessionManager.newSession(...) { session ->
        val maestro = session.maestro
        val device = session.device

        // ... existing code ...

        runBlocking {
            runMultipleFlows(
                maestro,
                device,
                chunkPlans,
                shardIndex,
                debugOutputPath,
                testOutputDir,
                logLevels,  // NEW
                logBufferSize  // NEW
            )
        }
    }
}
```

**Estimated effort:** 1 hour

---

## Testing Plan

### Unit Tests
- [x] LogCapture utility parsing
- [ ] TestExecutionSummary with logs
- [ ] Log level parsing
- [ ] HTML report generation with logs

### Integration Tests
```bash
# Test 1: Basic log capture
./maestro-cli/build/install/maestro/bin/maestro test \
  --include-logs \
  --format html \
  --output test-output/report.html \
  maestro-orchestra-models/test-logs.yaml

# Test 2: Filtered log levels
./maestro-cli/build/install/maestro/bin/maestro test \
  --include-logs=ERROR,WARN \
  --format html \
  --output test-output/report-errors.html \
  maestro-orchestra-models/test-logs.yaml

# Test 3: Custom buffer size
./maestro-cli/build/install/maestro/bin/maestro test \
  --include-logs \
  --log-buffer-size=10000 \
  --format html \
  --output test-output/report-large.html \
  maestro-orchestra-models/test-logs.yaml

# Test 4: Failed test (auto-capture)
./maestro-cli/build/install/maestro/bin/maestro test \
  --format html \
  --output test-output/report-failed.html \
  e2e/workspaces/demo_app/fail_visible.yaml
```

### Manual Verification
- [ ] Logs JSON file created in debug output
- [ ] Console Logs tab appears in HTML report
- [ ] Logs grouped by command
- [ ] Log level filtering works
- [ ] Search functionality works
- [ ] Failed tests show error logs automatically
- [ ] Performance acceptable with 5000+ logs
- [ ] Command sections can be collapsed/expanded

---

## Estimated Total Time Remaining

| Step | Task | Time |
|------|------|------|
| 5 | TestSuiteInteractor integration | 2-3 hours |
| 6 | JSON export | 1 hour |
| 7 | HTML reporter | 2 hours |
| 8 | CSS & JavaScript | 1 hour |
| 9 | ReporterFactory | 15 min |
| 10 | Wire everything together | 1 hour |
| Testing | Integration tests | 1 hour |
| **Total** | | **8-9 hours** |

---

## Git Commit History So Far

```
3ec9fe09 - Fix LogCaptureTest imports
42ec3aff - Add simple logcat-based log capture utility
a1a9971a - Add log capture methods to Driver interface
36b549f6 - Add LogEntry model and update TestExecutionSummary
aa383543 - Add --include-logs and --log-buffer-size CLI flags
```

---

## Next Steps

1. ✅ Create this summary document
2. ⏸️ Continue with Step 5: TestSuiteInteractor integration
3. ⏸️ Implement remaining steps systematically
4. ⏸️ Test end-to-end
5. ⏸️ Create final summary with usage examples

---

## Notes

- **Simple approach:** Using `adb logcat` for now, can upgrade to AndroidDriver gRPC later
- **One commit per step:** Makes review easier
- **Unit tests included:** Where practical
- **Vanilla JS/CSS only:** No framework dependencies
- **VS Code theme:** Dark theme for logs (familiar to developers)
- **Backward compatible:** Empty logs lists don't break existing reports
