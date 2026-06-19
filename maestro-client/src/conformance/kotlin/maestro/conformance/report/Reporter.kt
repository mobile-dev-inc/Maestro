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

    fun writeSummary(banner: String) {
        val all = cells.values.flatten()
        val failed = all.count { !it.verdict }
        val summary = linkedMapOf(
            "banner" to banner,
            "total" to all.size,
            "passed" to all.count { it.verdict },
            "failed" to failed,
            "cells" to cells.mapValues { (_, recs) ->
                recs.associate { it.command to verdictStr(it.verdict) }
            },
        )
        val json = mapper.writeValueAsString(summary)
        File(root, "summary.json").writeText(json)
        // summary.js DROPPED — replaced by data.js
        File(root, "data.js").writeText(buildDataJs(banner))
        File(root, "index.html").writeText(buildHtml())
    }

    private fun buildDataJs(banner: String): String {
        val allRecords = cells.values.flatten()
        val total = allRecords.size
        val passed = allRecords.count { it.verdict }
        val failed = total - passed

        // Parse api and framework from cell keys like "api34-native"
        val frameworks = cells.keys.mapNotNull { key ->
            val dash = key.indexOf('-')
            if (dash > 0) key.substring(dash + 1) else null
        }.distinct()
        val apis = cells.keys.mapNotNull { key ->
            val dash = key.indexOf('-')
            if (dash > 3) key.substring(3, dash).toIntOrNull() else null
        }.distinct().sorted()

        // Union of command names, first-seen order
        val commands = linkedSetOf<String>()
        cells.values.forEach { recs -> recs.forEach { commands.add(it.command) } }

        // Build per-cell data
        val cellsData = cells.mapValues { (_, recs) ->
            recs.associate { r ->
                r.command to linkedMapOf(
                    "verdict" to verdictStr(r.verdict),
                    "coverage" to r.coverage,
                    "oracleKind" to r.oracleKind.name,
                    "expected" to r.expected,
                    "actual" to r.actual,
                    "args" to r.args,
                    "failureReason" to r.failureReason,
                    "actMs" to r.actMs,
                    "totalMs" to r.totalMs,
                    "artifacts" to r.artifacts,
                )
            }
        }

        val data = linkedMapOf(
            "banner" to banner,
            "totals" to mapOf("total" to total, "passed" to passed, "failed" to failed),
            "frameworks" to frameworks,
            "apis" to apis,
            "commands" to commands.toList(),
            "cells" to cellsData,
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
    '<span class="fail">' + t.failed + ' ✗</span>';

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
      th.textContent = 'API ' + api;
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
        var td = document.createElement('td');
        var rec = D.cells[cellKey] && D.cells[cellKey][cmd];
        if (rec) {
          var verdict = rec.verdict;
          var cls = verdict === 'PASS' ? 'pass' : 'fail';
          var pill = document.createElement('span');
          pill.className = 'pill ' + cls;
          pill.textContent = verdict;
          pill.setAttribute('data-cell', cellKey);
          pill.setAttribute('data-command', cmd);
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
    var rec = D.cells[cellKey] && D.cells[cellKey][cmd];
    if (!rec) return;

    // Update URL
    history.replaceState(null, '', '?cell=' + encodeURIComponent(cellKey) + '&command=' + encodeURIComponent(cmd));

    var verdict = rec.verdict;
    var cls = verdict === 'PASS' ? 'pass' : 'fail';
    var parts = []; // build HTML as array then join

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
    var basePath = 'cells/' + cellKey + '/' + cmd + '/';

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
