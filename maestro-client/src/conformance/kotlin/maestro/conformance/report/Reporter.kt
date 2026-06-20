package maestro.conformance.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File

class Reporter(private val root: File) {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val cells = LinkedHashMap<String, List<CommandRecord>>()

    private fun verdictStr(p: Boolean) = if (p) "PASS" else "FAIL"

    fun commandDir(cell: String, command: String): File =
        File(root, "cells/$cell/$command").apply { mkdirs() }

    fun writeCommand(cell: String, record: CommandRecord) {
        val dir = commandDir(cell, record.command)
        val json = mapper.writeValueAsString(
            linkedMapOf(
                "command" to record.command,
                "coverage" to record.coverage,
                "args" to record.args,
                "oracle" to linkedMapOf(
                    "kind" to record.oracleKind.name,
                    "expected" to record.expected,
                    "actual" to record.actual,
                ),
                "verdict" to verdictStr(record.verdict),
                "failureReason" to record.failureReason,
                "timings" to mapOf("actMs" to record.actMs, "totalMs" to record.totalMs),
                "artifacts" to record.artifacts,
            )
        )
        File(dir, "command.json").writeText(json)
    }

    fun writeCell(cell: String, records: List<CommandRecord>) {
        cells[cell] = records
        records.forEach { writeCommand(cell, it) }
        val dir = File(root, "cells/$cell").apply { mkdirs() }
        File(dir, "cell.json").writeText(
            mapper.writeValueAsString(records.map {
                mapOf("command" to it.command, "verdict" to verdictStr(it.verdict))
            })
        )
    }

    fun writeApiError(cell: String, phase: String, error: String, stacktrace: String) {
        val cellDir = File(root, "cells/$cell").apply { mkdirs() }
        // Parse api and framework from cell like "api36-native"
        val dash = cell.indexOf('-')
        val apiNum = if (cell.startsWith("api") && dash > 3) cell.substring(3, dash).toIntOrNull() ?: 0 else 0
        val framework = if (dash > 0) cell.substring(dash + 1) else cell
        val json = mapper.writeValueAsString(linkedMapOf(
            "api" to apiNum,
            "framework" to framework,
            "phase" to phase,
            "error" to error,
            "stacktrace" to stacktrace,
        ))
        File(cellDir, "api-error.json").writeText(json)
    }

    fun writeProvisioningErrors(failedApis: List<Int>) {
        if (failedApis.isEmpty()) return
        File(root, "provisioning-errors.log").writeText(
            "APIs that failed to provision (skipped):\n" + failedApis.joinToString("\n") { "  API $it" }
        )
    }

    fun writeSummary(banner: String) {
        root.mkdirs()
        // Scan disk for up-to-date totals and cell verdicts
        val diskData = scanDisk()
        val summary = linkedMapOf(
            "banner" to banner,
            "total" to (diskData.passed + diskData.failed),
            "passed" to diskData.passed,
            "failed" to diskData.failed,
            "apiFailed" to diskData.apiFailed,
            "cells" to diskData.cellVerdicts,
        )
        val json = mapper.writeValueAsString(summary)
        File(root, "summary.json").writeText(json)
        // summary.js DROPPED — replaced by data.js
        File(root, "data.js").writeText(buildDataJs(banner))
        File(root, "index.html").writeText(buildHtml())
    }

    private data class DiskScanResult(
        val passed: Int,
        val failed: Int,
        val apiFailed: Int,
        val didNotRun: Int,
        val frameworks: List<String>,
        val apis: List<Int>,
        val commands: List<String>,
        val cellsData: Map<String, Map<String, Any?>>,
        val cellVerdicts: Map<String, Any>,
    )

    @Suppress("ComplexMethod")
    private fun scanDisk(): DiskScanResult {
        val cellsDir = File(root, "cells")
        val cellDirs = cellsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        val commandsOrdered = linkedSetOf<String>()
        val cellsData = linkedMapOf<String, Map<String, Any?>>()
        val cellVerdicts = linkedMapOf<String, Any>()
        val frameworksSet = linkedSetOf<String>()
        val apisSet = sortedSetOf<Int>()

        var passed = 0
        var failed = 0
        var apiFailedCount = 0

        for (cellDir in cellDirs.sortedBy { it.name }) {
            val cellName = cellDir.name
            val dash = cellName.indexOf('-')
            if (dash <= 0) continue
            val apiNum = if (cellName.startsWith("api") && dash > 3) cellName.substring(3, dash).toIntOrNull() else null
            val fw = cellName.substring(dash + 1)
            if (apiNum != null) apisSet += apiNum
            frameworksSet += fw

            val apiErrorFile = File(cellDir, "api-error.json")
            if (apiErrorFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                val errMap = mapper.readValue(apiErrorFile, Map::class.java) as Map<String, Any?>
                cellsData[cellName] = mapOf(
                    "__apiError" to linkedMapOf(
                        "phase" to errMap["phase"],
                        "error" to errMap["error"],
                        "stacktrace" to errMap["stacktrace"],
                    )
                )
                cellVerdicts[cellName] = mapOf("__apiError" to true)
                apiFailedCount++
                continue
            }

            // Read command subdirs
            val commandEntries = linkedMapOf<String, Any?>()
            val commandDirs = cellDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (cmdDir in commandDirs.sortedBy { it.name }) {
                val cmdJson = File(cmdDir, "command.json")
                if (!cmdJson.exists()) continue
                @Suppress("UNCHECKED_CAST")
                val rec = mapper.readValue(cmdJson, Map::class.java) as Map<String, Any?>
                val cmdName = rec["command"] as? String ?: cmdDir.name
                commandsOrdered += cmdName
                val oracle = rec["oracle"] as? Map<*, *>
                val timings = rec["timings"] as? Map<*, *>
                val verdict = rec["verdict"] as? String ?: "FAIL"
                if (verdict == "PASS") passed++ else failed++
                commandEntries[cmdName] = linkedMapOf(
                    "verdict" to verdict,
                    "coverage" to rec["coverage"],
                    "oracleKind" to oracle?.get("kind"),
                    "expected" to oracle?.get("expected"),
                    "actual" to oracle?.get("actual"),
                    "args" to rec["args"],
                    "failureReason" to rec["failureReason"],
                    "actMs" to timings?.get("actMs"),
                    "totalMs" to timings?.get("totalMs"),
                    "artifacts" to rec["artifacts"],
                )
            }
            cellsData[cellName] = commandEntries
            cellVerdicts[cellName] = commandEntries.mapValues { (_, v) ->
                (v as? Map<*, *>)?.get("verdict") ?: "FAIL"
            }
        }

        val nonErrorCells = cellsData.count { (_, v) -> !v.containsKey("__apiError") }
        val didNotRun = (commandsOrdered.size * nonErrorCells) - passed - failed

        return DiskScanResult(
            passed = passed,
            failed = failed,
            apiFailed = apiFailedCount,
            didNotRun = if (didNotRun > 0) didNotRun else 0,
            frameworks = frameworksSet.toList(),
            apis = apisSet.toList(),
            commands = commandsOrdered.toList(),
            cellsData = cellsData,
            cellVerdicts = cellVerdicts,
        )
    }

    @Suppress("ComplexMethod")
    private fun buildDataJs(banner: String): String {
        val diskData = scanDisk()

        val data = linkedMapOf(
            "banner" to banner,
            "totals" to mapOf(
                "passed" to diskData.passed,
                "failed" to diskData.failed,
                "apiFailed" to diskData.apiFailed,
                "didNotRun" to diskData.didNotRun,
            ),
            "frameworks" to diskData.frameworks,
            "apis" to diskData.apis,
            "commands" to diskData.commands,
            "cells" to diskData.cellsData,
        )
        val dataJson = mapper.writeValueAsString(data)
        return "window.DATA = $dataJson;"
    }

    @Suppress("LongMethod")
    private fun buildHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Driver Conformance</title>
  <script src="./data.js"></script>
  <style>
    :root {
      --bg: #0f1115;
      --panel: #161a22;
      --panel-border: #232936;
      --row-hover: #1d2330;
      --text: #e6e8eb;
      --muted: #8a93a3;
      --pass-bg: #14361f;
      --pass-fg: #4cd07d;
      --fail-bg: #3a1414;
      --fail-fg: #ff6464;
      --skip-bg: #2a2f3a;
      --skip-fg: #9aa3b3;
    }
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      font-size: 14px;
      line-height: 1.5;
      min-height: 100vh;
    }
    #app { display: flex; flex-direction: column; min-height: 100vh; }
    header {
      padding: 20px 28px 16px;
      border-bottom: 1px solid var(--panel-border);
      display: flex;
      align-items: baseline;
      gap: 16px;
      flex-wrap: wrap;
    }
    header h1 { font-size: 18px; font-weight: 600; letter-spacing: .02em; }
    header .banner { color: var(--muted); font-size: 13px; }
    header .tally { margin-left: auto; font-size: 13px; white-space: nowrap; }
    .tally .pass { color: var(--pass-fg); }
    .tally .fail { color: var(--fail-fg); }
    main { display: flex; flex: 1; overflow: hidden; }
    #matrices {
      flex: 1;
      overflow-y: auto;
      padding: 20px 28px;
    }
    .fw-section { margin-bottom: 32px; }
    .fw-section h2 {
      font-size: 13px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: .08em;
      color: var(--muted);
      margin-bottom: 10px;
    }
    table { border-collapse: collapse; width: 100%; }
    th, td {
      padding: 7px 12px;
      text-align: center;
      border: 1px solid var(--panel-border);
    }
    th {
      background: var(--panel);
      color: var(--muted);
      font-size: 12px;
      font-weight: 600;
      white-space: nowrap;
    }
    th.cmd-col { text-align: left; min-width: 140px; }
    tr:hover td { background: var(--row-hover); }
    td.cmd-name {
      text-align: left;
      font-family: ui-monospace, Menlo, monospace;
      font-size: 12px;
      color: var(--text);
    }
    .pill {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      letter-spacing: .04em;
    }
    .pill.pass { background: var(--pass-bg); color: var(--pass-fg); }
    .pill.fail { background: var(--fail-bg); color: var(--fail-fg); }
    .pill.dnr { background: var(--skip-bg); color: var(--skip-fg); }
    .pill:hover { filter: brightness(1.15); }
    td.skip { color: var(--muted); font-size: 13px; }
    #detail {
      width: 420px;
      min-width: 300px;
      background: var(--panel);
      border-left: 1px solid var(--panel-border);
      overflow-y: auto;
      padding: 20px;
      display: none;
    }
    #detail.open { display: block; }
    .detail-title {
      font-size: 13px;
      font-weight: 600;
      margin-bottom: 6px;
      font-family: ui-monospace, Menlo, monospace;
    }
    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .05em;
      margin-left: 8px;
    }
    .badge.pass { background: var(--pass-bg); color: var(--pass-fg); }
    .badge.fail { background: var(--fail-bg); color: var(--fail-fg); }
    .section-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: .08em;
      color: var(--muted);
      margin: 14px 0 4px;
    }
    pre {
      background: var(--bg);
      border: 1px solid var(--panel-border);
      border-radius: 4px;
      padding: 8px 10px;
      font-size: 11px;
      font-family: ui-monospace, Menlo, monospace;
      overflow-x: auto;
      color: var(--text);
      white-space: pre-wrap;
      word-break: break-all;
    }
    .mono { font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
    .failure-reason {
      background: var(--fail-bg);
      color: var(--fail-fg);
      border-radius: 4px;
      padding: 6px 10px;
      font-size: 12px;
      margin-top: 6px;
    }
    video {
      width: 100%;
      border-radius: 6px;
      background: #000;
      margin-top: 6px;
      display: block;
    }
    .screenshot { margin-top: 6px; }
    .screenshot img { max-width: 360px; width: 100%; border-radius: 4px; display: block; }
    .logcat-link { font-size: 12px; }
    .logcat-link a { color: var(--pass-fg); text-decoration: none; }
    .logcat-link a:hover { text-decoration: underline; }
    .close-btn {
      float: right;
      background: none;
      border: none;
      color: var(--muted);
      font-size: 18px;
      cursor: pointer;
      line-height: 1;
      padding: 0 4px;
    }
    .close-btn:hover { color: var(--text); }
    .empty-detail {
      color: var(--muted);
      font-size: 13px;
      margin-top: 60px;
      text-align: center;
    }
  </style>
</head>
<body>
<div id="app">
  <header>
    <h1>Driver Conformance</h1>
    <span class="banner" id="hdr-banner"></span>
    <span class="tally" id="hdr-tally"></span>
  </header>
  <main>
    <div id="matrices"></div>
    <div id="detail">
      <button class="close-btn" id="close-detail" title="Close">&times;</button>
      <div id="detail-content"><p class="empty-detail">Select a cell to inspect</p></div>
    </div>
  </main>
</div>
<script>
(function () {
  var D = window.DATA;
  if (!D) { document.body.innerHTML = '<p style="color:#ff6464;padding:20px">data.js not loaded.</p>'; return; }

  // Header
  document.getElementById('hdr-banner').textContent = D.banner;
  var t = D.totals;
  document.getElementById('hdr-tally').innerHTML =
    '<span class="pass">' + t.passed + ' ✓</span>' +
    ' &middot; ' +
    '<span class="fail">' + t.failed + ' ✗</span>' +
    (t.apiFailed ? ' &middot; <span class="fail">' + t.apiFailed + ' API failed</span>' : '');

  // Build matrices
  var matrices = document.getElementById('matrices');
  D.frameworks.forEach(function (fw) {
    var section = document.createElement('section');
    section.className = 'fw-section';
    var h2 = document.createElement('h2');
    h2.textContent = fw;
    section.appendChild(h2);

    var table = document.createElement('table');
    // Header row
    var thead = document.createElement('thead');
    var hrow = document.createElement('tr');
    var th0 = document.createElement('th');
    th0.className = 'cmd-col';
    th0.textContent = 'Command';
    hrow.appendChild(th0);
    D.apis.forEach(function (api) {
      var th = document.createElement('th');
      var cellKey = 'api' + api + '-' + fw;
      var cellData = D.cells[cellKey];
      var hasApiError = cellData && cellData['__apiError'];
      th.innerHTML = 'API ' + api + (hasApiError ? ' <span style="color:var(--fail-fg);font-size:10px">&#9888; FAILED</span>' : '');
      hrow.appendChild(th);
    });
    thead.appendChild(hrow);
    table.appendChild(thead);

    // Body rows
    var tbody = document.createElement('tbody');
    D.commands.forEach(function (cmd) {
      var tr = document.createElement('tr');
      var tdName = document.createElement('td');
      tdName.className = 'cmd-name';
      tdName.textContent = cmd;
      tr.appendChild(tdName);

      D.apis.forEach(function (api) {
        var cellKey = 'api' + api + '-' + fw;
        var cellData = D.cells[cellKey];
        var td = document.createElement('td');
        var rec = cellData && cellData[cmd];
        if (rec) {
          var verdict = rec.verdict;
          var cls = verdict === 'PASS' ? 'pass' : 'fail';
          var pill = document.createElement('span');
          pill.className = 'pill ' + cls;
          pill.textContent = verdict === 'PASS' ? '✓' : '✗';
          pill.setAttribute('data-cell', cellKey);
          pill.setAttribute('data-command', cmd);
          pill.addEventListener('click', function () { openDetail(cellKey, cmd); });
          td.appendChild(pill);
        } else if (cellData && cellData['__apiError']) {
          var pill = document.createElement('span');
          pill.className = 'pill dnr';
          pill.textContent = '○';
          pill.setAttribute('data-cell', cellKey);
          pill.addEventListener('click', function () { openDetail(cellKey, cmd); });
          td.appendChild(pill);
        } else {
          td.className = 'skip';
          td.textContent = '–';
        }
        tr.appendChild(td);
      });

      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    section.appendChild(table);
    matrices.appendChild(section);
  });

  // Deep-link on load
  var params = new URLSearchParams(window.location.search);
  var initCell = params.get('cell');
  var initCmd = params.get('command');
  if (initCell && initCmd) { openDetail(initCell, initCmd); }

  // Close button
  document.getElementById('close-detail').addEventListener('click', function () {
    document.getElementById('detail').classList.remove('open');
    history.replaceState(null, '', window.location.pathname);
  });

  function openDetail(cellKey, cmd) {
    var cellData = D.cells[cellKey];
    var rec = cellData && cellData[cmd];
    var apiErr = cellData && cellData['__apiError'];

    // Update URL
    history.replaceState(null, '', '?cell=' + encodeURIComponent(cellKey) + '&command=' + encodeURIComponent(cmd));

    var parts = [];

    if (apiErr) {
      // API provisioning/run error detail
      parts.push('<div class="detail-title">' + esc(cellKey) +
        ' <span class="badge fail">API FAILED</span></div>');
      parts.push('<div class="section-label">Phase</div>');
      parts.push('<div class="mono">' + esc(apiErr.phase) + '</div>');
      parts.push('<div class="section-label">Error</div>');
      parts.push('<div class="failure-reason">' + esc(apiErr.error) + '</div>');
      parts.push('<div class="section-label">Stacktrace</div>');
      parts.push('<pre>' + esc(apiErr.stacktrace) + '</pre>');
      parts.push('<div style="margin-top:14px;color:var(--muted);font-size:12px">Command <em>' + esc(cmd) + '</em> was not run — API failed to ' + esc(apiErr.phase) + '.</div>');
    } else if (rec) {
      // Normal command record
      var verdict = rec.verdict;
      var cls = verdict === 'PASS' ? 'pass' : 'fail';

      parts.push('<div class="detail-title">' + esc(cmd) + ' &middot; ' + esc(cellKey) +
        '<span class="badge ' + cls + '">' + esc(verdict) + '</span></div>');

      // Oracle
      parts.push('<div class="section-label">Oracle</div>');
      parts.push('<div class="mono" style="margin-bottom:4px">Kind: ' + esc(rec.oracleKind) + '</div>');
      parts.push('<div class="section-label">Expected</div>');
      parts.push('<pre>' + esc(JSON.stringify(rec.expected, null, 2)) + '</pre>');
      parts.push('<div class="section-label">Actual</div>');
      parts.push('<pre>' + esc(JSON.stringify(rec.actual, null, 2)) + '</pre>');

      // Args
      parts.push('<div class="section-label">Args</div>');
      parts.push('<pre>' + esc(JSON.stringify(rec.args, null, 2)) + '</pre>');

      // Coverage + timings
      parts.push('<div class="section-label">Coverage</div>');
      parts.push('<div class="mono">' + esc(rec.coverage) + '</div>');
      parts.push('<div class="section-label">Timings</div>');
      parts.push('<div class="mono">act ' + rec.actMs + 'ms / total ' + rec.totalMs + 'ms</div>');

      // Failure reason
      if (rec.failureReason) {
        parts.push('<div class="section-label">Failure Reason</div>');
        parts.push('<div class="failure-reason">' + esc(rec.failureReason) + '</div>');
      }

      // Artifacts
      var artifacts = rec.artifacts || [];
      var basePath = 'cells/' + encodeURIComponent(cellKey) + '/' + encodeURIComponent(cmd) + '/';

      if (artifacts.indexOf('recording.mp4') !== -1) {
        parts.push('<div class="section-label">Recording</div>');
        parts.push('<video controls preload="metadata" src="' + basePath + 'recording.mp4"></video>');
      }
      if (artifacts.indexOf('after.png') !== -1) {
        parts.push('<div class="section-label">Screenshot</div>');
        parts.push('<div class="screenshot"><img src="' + basePath + 'after.png" alt="after screenshot"></div>');
      }
      if (artifacts.indexOf('logcat-slice.txt') !== -1) {
        parts.push('<div class="section-label">Logcat</div>');
        parts.push('<div class="logcat-link"><a href="' + basePath + 'logcat-slice.txt" target="_blank">logcat-slice.txt</a></div>');
      }
    } else {
      // Plain skip — no record, no api error
      parts.push('<div class="detail-title">' + esc(cmd) + ' &middot; ' + esc(cellKey) + '</div>');
      parts.push('<p style="color:var(--muted);font-size:13px;margin-top:12px">This command was not run for this cell.</p>');
    }

    document.getElementById('detail-content').innerHTML = parts.join('\n');
    document.getElementById('detail').classList.add('open');
  }

  function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
})();
</script>
</body>
</html>
""".trimIndent()
}
