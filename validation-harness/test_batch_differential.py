# validation-harness/test_batch_differential.py
import ast, json, os
import pytest
import batch_differential as bd
import classification


# --- Regression guard: HARNESS_MODULES must cover run_differential's TRANSITIVE
# local-import closure. The remote batch ships a FIXED file list to each host, so
# any local module reachable from run_differential.py — directly OR transitively
# (e.g. provenance -> manifest) — that isn't shipped crashes the remote run with
# ModuleNotFoundError. The unit suite otherwise misses it: tests run inside the
# harness dir where every module resolves locally.

def _closure_of(harness_dir, seed):
    """Transitive closure of LOCAL module imports starting from <seed>.py.

    A dotted name is 'local' when <name>.py or <name>/__init__.py exists in the
    harness dir (as opposed to a stdlib/third-party module). Returns two sets:
    local .py module basenames, and local package dir names.
    """
    def _is_local_module(name):
        return os.path.isfile(os.path.join(harness_dir, name + ".py"))

    def _is_local_pkg(name):
        return os.path.isfile(os.path.join(harness_dir, name, "__init__.py"))

    def _imports(pyfile):
        with open(pyfile) as fh:
            tree = ast.parse(fh.read(), filename=pyfile)
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for a in node.names:
                    names.add(a.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                # only absolute (level 0) imports name a shippable top-level module
                if node.module and node.level == 0:
                    names.add(node.module.split(".")[0])
        return names

    modules, pkgs, seen = set(), set(), set()
    stack = [seed]
    while stack:
        mod = stack.pop()
        if mod in seen:
            continue
        seen.add(mod)
        modules.add(mod)
        for name in _imports(os.path.join(harness_dir, mod + ".py")):
            if name in seen:
                continue
            if _is_local_module(name):
                stack.append(name)
            elif _is_local_pkg(name):
                pkgs.add(name)
    return modules, pkgs


def test_harness_modules_covers_transitive_import_closure():
    harness_dir = os.path.dirname(os.path.abspath(bd.__file__))
    modules, pkgs = _closure_of(harness_dir, "run_differential")
    closure_files = {m + ".py" for m in modules}
    missing_modules = closure_files - set(bd.HARNESS_MODULES)
    assert not missing_modules, (
        "HARNESS_MODULES is missing transitive local imports of run_differential.py "
        f"(they'd crash the remote run with ModuleNotFoundError): {sorted(missing_modules)}"
    )
    missing_pkgs = pkgs - set(bd.HARNESS_DIRS)
    assert not missing_pkgs, (
        f"HARNESS_DIRS is missing local package dirs in the closure: {sorted(missing_pkgs)}"
    )

class RecRunner:
    def __init__(self): self.calls = []
    def __call__(self, argv, cwd=None, capture_output=True, text=True, check=False, **kw):
        self.calls.append({"argv": argv, "cwd": cwd})
        class R: stdout=""; stderr=""; returncode=0
        return R()

def _fake_installdist_tree(root, device_dir, cli2_dir, cli3_dir):
    dbin = os.path.join(device_dir, "build/install/maestro-device/bin")
    os.makedirs(dbin); open(os.path.join(dbin, "maestro-device"), "w").close()
    for d in (cli2_dir, cli3_dir):
        cbin = os.path.join(d, "maestro-cli/build/install/maestro/bin")
        os.makedirs(cbin); open(os.path.join(cbin, "maestro"), "w").close()

def test_gradle_build_runs_tasks_in_project_dir():
    r = RecRunner()
    bd.gradle_build("/proj", [":maestro-cli:installDist"], runner=r)
    assert r.calls[0]["cwd"] == "/proj"
    assert "./gradlew" in r.calls[0]["argv"][0] or "gradlew" in " ".join(r.calls[0]["argv"])
    assert ":maestro-cli:installDist" in r.calls[0]["argv"]

def test_resolve_artifacts_returns_absolute_paths(tmp_path):
    dev, c2, c3 = str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3")
    _fake_installdist_tree(tmp_path, dev, c2, c3)
    art = bd.resolve_artifacts(dev, c2, c3)
    assert art["device_bin"].endswith("build/install/maestro-device/bin/maestro-device")
    assert art["cli_2x"].endswith("maestro-cli/build/install/maestro/bin/maestro")
    assert os.path.isabs(art["device_bin"])

def test_resolve_artifacts_missing_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        bd.resolve_artifacts(str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3"))

def test_cmd_build_writes_manifest(tmp_path):
    dev, c2, c3 = str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3")
    _fake_installdist_tree(tmp_path, dev, c2, c3)
    with open(os.path.join(c3, "devicecore.version"), "w") as fh:
        fh.write("0.1.0-ba529198f969\n")
    args = bd._ns(work_dir=str(tmp_path/"bo"), device_dir=dev, cli_2x_dir=c2, cli_3x_dir=c3)
    manifest = bd.cmd_build(args, runner=RecRunner())
    written = json.load(open(os.path.join(str(tmp_path/"bo"), "build-manifest.json")))
    assert written == manifest
    assert set(manifest) == {"device_bin", "cli_2x", "cli_3x"}


def test_cmd_build_writes_versioned_manifest(tmp_path):
    dev, c2, c3 = str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3")
    _fake_installdist_tree(tmp_path, dev, c2, c3)
    # The 3x role reads the effective device-core version from the maestro root
    # (cli_3x_dir); .local override wins but the committed pin is enough here.
    with open(os.path.join(c3, "devicecore.version"), "w") as fh:
        fh.write("0.1.0-ba529198f969\n")
    args = bd._ns(work_dir=str(tmp_path/"bo"), device_dir=dev, cli_2x_dir=c2, cli_3x_dir=c3)
    bd.cmd_build(args, runner=RecRunner())
    m = json.load(open(os.path.join(str(tmp_path/"bo"), "manifest.json")))
    by_role = {b["role"]: b for b in m["binaries"]}
    assert set(by_role) == {"2x", "3x", "device"}
    assert by_role["3x"]["deviceCoreVersion"] == "0.1.0-ba529198f969"
    assert by_role["2x"].get("deviceCoreVersion") is None
    assert by_role["device"].get("deviceCoreVersion") is None
    for b in m["binaries"]:
        assert b["contentHash"].startswith("sha256:")
        assert "repo" in b and "gitSha" in b and "dirty" in b and "buildTime" in b


def test_cmd_build_default_writes_no_bin(tmp_path):
    dev, c2, c3 = str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3")
    _fake_installdist_tree(tmp_path, dev, c2, c3)
    with open(os.path.join(c3, "devicecore.version"), "w") as fh:
        fh.write("0.1.0-ba529198f969\n")
    work = str(tmp_path/"bo")
    args = bd._ns(work_dir=work, device_dir=dev, cli_2x_dir=c2, cli_3x_dir=c3)
    bd.cmd_build(args, runner=RecRunner())
    assert not os.path.isdir(os.path.join(work, "bin"))


def test_cmd_build_vendor_bins_copies_trees(tmp_path):
    dev, c2, c3 = str(tmp_path/"dev"), str(tmp_path/"c2"), str(tmp_path/"c3")
    _fake_installdist_tree(tmp_path, dev, c2, c3)
    with open(os.path.join(c3, "devicecore.version"), "w") as fh:
        fh.write("0.1.0-ba529198f969\n")
    work = str(tmp_path/"bo")
    args = bd._ns(work_dir=work, device_dir=dev, cli_2x_dir=c2, cli_3x_dir=c3,
                  vendor_bins=True)
    bd.cmd_build(args, runner=RecRunner())
    bin_dir = os.path.join(work, "bin")
    assert os.path.isdir(bin_dir) and len(os.listdir(bin_dir)) >= 1


# --- Task 6: partition subcommand ---

def _mk_folder(tmp_path, name, platform):
    d = tmp_path / name
    (d / "workspace").mkdir(parents=True)
    (d / "metadata.json").write_text(json.dumps({"platform": platform}))
    return str(d)

INV_FIX = """\
all:
  children:
    ios_agents:
      hosts:
        m4-1:
          ansible_host: 10.0.0.11
          ansible_user: admin
          ansible_password: pw
    android_agents:
      hosts:
        m2-1:
          ansible_host: 10.0.0.21
          ansible_user: admin
          ansible_password: pw
"""

def test_cmd_partition_writes_manifest(tmp_path):
    _mk_folder(tmp_path, "run_a", "ANDROID")
    _mk_folder(tmp_path, "run_i", "IOS")
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)
    args = bd._ns(work_dir=str(tmp_path/"bo"), inventory=str(inv),
                  ios_hosts="m4-1", android_hosts="m2-1",
                  folders=[str(tmp_path/"run_*")])
    out = bd.cmd_partition(args)
    manifest = json.load(open(os.path.join(str(tmp_path/"bo"), "partition.json")))
    assert manifest == out
    assert manifest["m2-1"]["platform"] == "ANDROID"
    assert any(f.endswith("run_a") for f in manifest["m2-1"]["folders"])
    assert manifest["m4-1"]["platform"] == "IOS"
    # no credential ever lands in the manifest
    assert "pw" not in json.dumps(manifest)

def test_cmd_partition_rejects_unknown_host(tmp_path):
    _mk_folder(tmp_path, "run_a", "ANDROID")
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)
    args = bd._ns(work_dir=str(tmp_path/"bo"), inventory=str(inv),
                  ios_hosts="m4-1", android_hosts="ghost-9",
                  folders=[str(tmp_path/"run_*")])
    with pytest.raises(ValueError):
        bd.cmd_partition(args)


# --- Task 7: dispatch subcommand + --smoke gate ---

class FakeTransport:
    """Stands in for the remote module: records ssh/scp/run/poll, scripts idle."""
    def __init__(self, idle=True):
        self.idle = idle
        self.ssh_calls = []; self.scp_calls = []; self.run_scripts = []
    # mirror remote.* surface used by dispatch
    def ssh_run(self, creds, script, runner=None, timeout=None):
        self.ssh_calls.append(script)
        class R: stdout = "idle-probe-output"; stderr=""; returncode=0
        return R()
    def scp_put(self, creds, local, remote_path, runner=None):
        self.scp_calls.append((local, remote_path))
    def claim_probe_script(self, platform): return f"probe-{platform}"
    def host_is_idle(self, platform, out): return self.idle
    def remote_run_script(self, **kw):
        self.run_scripts.append(kw); return "nohup ... &"

def _write_manifests(tmp_path):
    work = tmp_path / "bo"; work.mkdir()
    (work / "build-manifest.json").write_text(json.dumps(
        {"device_bin": "/a/dev", "cli_2x": "/a/2x", "cli_3x": "/a/3x"}))
    (work / "partition.json").write_text(json.dumps({
        "m4-1": {"platform": "IOS", "folders": ["/c/run_i1", "/c/run_i2"]},
        "m2-1": {"platform": "ANDROID", "folders": ["/c/run_a1", "/c/run_a2"]},
    }))
    # cmd_build writes this ~1 KB manifest to work_dir; dispatch must ship it so each
    # remote run can emit provenance.json (--manifest).
    (work / "manifest.json").write_text(json.dumps({"binaries": {"cli3x": "abc"}}))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)
    return str(work), str(inv)

def test_smoke_selection_one_each_one_folder():
    manifest = {
        "m4-1": {"platform": "IOS", "folders": ["i1", "i2"]},
        "m4-2": {"platform": "IOS", "folders": ["i3"]},
        "m2-1": {"platform": "ANDROID", "folders": ["a1", "a2"]},
    }
    sel = bd.smoke_selection(manifest)
    assert len(sel) == 2
    ios = [h for h, e in sel.items() if e["platform"] == "IOS"][0]
    andr = [h for h, e in sel.items() if e["platform"] == "ANDROID"][0]
    assert len(sel[ios]["folders"]) == 1 and len(sel[andr]["folders"]) == 1

def test_dispatch_smoke_hits_one_ios_one_android_and_stops(tmp_path):
    work, inv = _write_manifests(tmp_path)
    t = FakeTransport(idle=True)
    args = bd._ns(work_dir=work, inventory=inv, smoke=True,
                  remote_root="~/scratch/dcdiff")
    state = bd.cmd_dispatch(args, transport=t)
    assert set(e["status"] for e in state["hosts"]) == {"running"}
    assert len(state["hosts"]) == 2                      # exactly one per platform
    # each dispatched host got exactly one folder in its run script
    assert all(len(rs["folders"]) == 1 for rs in t.run_scripts)
    # no credential in the persisted state
    assert "pw" not in json.dumps(state)

def test_dispatch_skips_busy_host_never_self_selects(tmp_path):
    work, inv = _write_manifests(tmp_path)
    t = FakeTransport(idle=False)
    args = bd._ns(work_dir=work, inventory=inv, smoke=True, remote_root="~/scratch")
    state = bd.cmd_dispatch(args, transport=t)
    assert all(e["status"] == "skipped-busy" for e in state["hosts"])
    assert t.run_scripts == []                            # nothing ran on a busy host


_CREDS = type("C", (), {"ip": "10.0.0.21", "user": "admin", "password": "pw"})()
_ART = {"device_bin_tree": "/a/maestro-device",
        "cli_2x_tree": {"src": "/a/2x/maestro", "alias": "2x"},
        "cli_3x_tree": {"src": "/a/3x/maestro", "alias": "3x"}}

def test_dispatch_namespaces_colliding_basenames(tmp_path):
    # SF-4: two folders share the basename run_1; flattened to corpus/ they'd
    # overwrite each other (and collide as runIds). Each must land distinctly.
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1", "/proj_b/run_1"]}
    res = bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t)
    assert res["status"] == "running"
    corpus_targets = [rp for (lp, rp) in t.scp_calls if "corpus" in rp]
    assert len(corpus_targets) == 2
    assert len(set(corpus_targets)) == 2          # distinct scp targets, no collision
    rs = t.run_scripts[0]
    assert len(set(rs["folders"])) == 2           # distinct run-script folder args
    assert rs["folders"] == ["corpus/0/run_1", "corpus/1/run_1"]

def test_dispatch_ships_manifest_and_passes_it_to_run_script(tmp_path):
    # Fix 2: the batch path emitted no provenance.json because manifest.json was
    # never shipped and run_differential ran WITHOUT --manifest. Ship the manifest
    # to the host and reference it so each remote run writes provenance.json.
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"binaries": {"cli3x": "abc"}}))
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t,
                     manifest_path=str(manifest))
    manifest_scps = [(lp, rp) for (lp, rp) in t.scp_calls if lp == str(manifest)]
    assert manifest_scps == [(str(manifest), "~/scratch/m2-1/manifest.json")]
    # and the run script references the shipped copy by its remote-relative path
    assert t.run_scripts[0]["manifest"] == "manifest.json"


def test_dispatch_omits_manifest_when_absent(tmp_path):
    # No manifest built (or path missing) -> nothing shipped, run script gets none.
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t,
                     manifest_path=str(tmp_path / "nope.json"))
    assert not any("manifest.json" in rp for (_, rp) in t.scp_calls)
    assert t.run_scripts[0].get("manifest") is None


def test_cmd_dispatch_ships_workdir_manifest(tmp_path):
    # End-to-end through cmd_dispatch: the manifest.json cmd_build wrote to work_dir
    # is shipped to each selected host.
    work, inv = _write_manifests(tmp_path)
    t = FakeTransport(idle=True)
    args = bd._ns(work_dir=work, inventory=inv, smoke=True,
                  remote_root="~/scratch/dcdiff")
    bd.cmd_dispatch(args, transport=t)
    manifest_targets = [rp for (lp, rp) in t.scp_calls if lp.endswith("manifest.json")]
    assert manifest_targets                                  # at least one host got it
    assert all(rp.endswith("/manifest.json") for rp in manifest_targets)
    assert all(rs["manifest"] == "manifest.json" for rs in t.run_scripts)


def test_dispatch_defaults_remote_python_to_brew(tmp_path):
    # bare python3 on the hosts is macOS 3.9; the run must default to the brew
    # interpreter (>=3.10) so run_folder.py's PEP-604 unions import cleanly.
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t)
    assert t.run_scripts[0]["python_bin"] == "/opt/homebrew/bin/python3"


def test_dispatch_threads_remote_python_override(tmp_path):
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t,
                     remote_python="/usr/bin/python3.11")
    assert t.run_scripts[0]["python_bin"] == "/usr/bin/python3.11"


def test_dispatch_cleans_cli_staging_before_scp(tmp_path):
    # NH-2: a mid-run death can leave art/maestro behind, making the next scp -r
    # nest as art/maestro/maestro. Clean the staging path before each CLI scp.
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t)
    staging_cleans = [s for s in t.ssh_calls
                      if "rm -rf ~/scratch/m2-1/art/maestro" in s and "mv" not in s]
    assert len(staging_cleans) == 2               # once per CLI tree (2x, 3x)

def test_dispatch_clears_stale_remote_out_before_run(tmp_path):
    # 3a: the remote scratch out/ can retain run_* dirs (and a stale DONE) from a
    # prior batch; collect would merge them with the current run's results. Dispatch
    # must clear the per-batch out/ before running so collect only ever sees this
    # batch's runs.
    t = FakeTransport(idle=True)
    entry = {"platform": "ANDROID", "folders": ["/proj_a/run_1"]}
    bd.dispatch_host("m2-1", entry, _CREDS, _ART, "~/scratch", t)
    out_clears = [s for s in t.ssh_calls if "rm -rf ~/scratch/m2-1/out" in s]
    assert len(out_clears) == 1                       # out/ is cleared exactly once
    # it must happen BEFORE the detached run script is issued
    run_idx = t.ssh_calls.index([s for s in t.ssh_calls if "nohup" in s][0]) \
        if any("nohup" in s for s in t.ssh_calls) else len(t.ssh_calls)
    assert t.ssh_calls.index(out_clears[0]) < run_idx
    # art/ and corpus/ hold freshly-pushed inputs — they must NOT be wiped
    assert not any("rm -rf ~/scratch/m2-1/art\b" in s for s in t.ssh_calls)
    assert not any(s.strip() == "rm -rf ~/scratch/m2-1/corpus" for s in t.ssh_calls)


def test_cmd_poll_reports_done_and_waiting(tmp_path):
    work = tmp_path / "bo"; work.mkdir()
    (work / "dispatch-state.json").write_text(json.dumps({
        "remote_root": "~/scratch",
        "hosts": [
            {"host": "m2-1", "status": "running", "remote_dir": "~/scratch/m2-1"},
            {"host": "m4-1", "status": "running", "remote_dir": "~/scratch/m4-1"},
            {"host": "m4-2", "status": "skipped-busy", "remote_dir": "~/scratch/m4-2"},
        ],
    }))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)
    class T:
        def __init__(self): self.paths = []
        def poll_done(self, creds, done_path):
            self.paths.append(done_path)
            return "m2-1" in done_path
    t = T()
    args = bd._ns(work_dir=str(work), inventory=str(inv))
    res = bd.cmd_poll(args, transport=t)
    assert res == {"m2-1": True, "m4-1": False}   # only running hosts, skipped ones ignored
    assert all(p.endswith("/out/DONE") for p in t.paths)


def test_cmd_poll_flags_crashed_run_from_exit_status(tmp_path, capsys):
    # 3b: a finished run whose sentinel carries a nonzero exit status crashed at
    # startup (e.g. ModuleNotFoundError) — the poll gate must surface that instead
    # of reporting a plain DONE that reads as success.
    work = tmp_path / "bo"; work.mkdir()
    (work / "dispatch-state.json").write_text(json.dumps({
        "remote_root": "~/scratch",
        "hosts": [
            {"host": "m2-1", "status": "running", "remote_dir": "~/scratch/m2-1"},
            {"host": "m4-1", "status": "running", "remote_dir": "~/scratch/m4-1"},
        ],
    }))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)

    class T:
        def poll_done(self, creds, done_path):
            return True                              # both finished
        def done_status(self, creds, done_path):
            return 1 if "m2-1" in done_path else 0   # m2-1 crashed, m4-1 clean
    args = bd._ns(work_dir=str(work), inventory=str(inv))
    res = bd.cmd_poll(args, transport=T())
    assert res == {"m2-1": True, "m4-1": True}       # both finished (existence)
    out = capsys.readouterr().out
    assert "CRASHED" in out and "m2-1" in out         # crash surfaced for m2-1
    # the clean host is a plain DONE, not flagged as crashed
    m4_line = [ln for ln in out.splitlines() if ln.startswith("m4-1:")][0]
    assert "CRASHED" not in m4_line


# --- Task 8: collect subcommand + merge_reports / diverging_folders ---

def test_merge_reports_concatenates_and_resums():
    r1 = {"folders": [{"runId": "a", "status": "ok", "diverge": 0}],
          "totalFolders": 1, "ok": 1, "incomplete": 0, "errors": 0}
    r2 = {"folders": [{"runId": "b", "status": "incomplete", "diverge": 0},
                      {"runId": "c", "status": "ok", "diverge": 3}],
          "totalFolders": 2, "ok": 1, "incomplete": 1, "errors": 0}
    agg = bd.merge_reports([r1, r2])
    assert agg["totalFolders"] == 3
    assert agg["ok"] == 2 and agg["incomplete"] == 1 and agg["errors"] == 0
    assert [f["runId"] for f in agg["folders"]] == ["a", "b", "c"]

def test_diverging_folders_picks_only_diverge_gt_zero():
    agg = {"folders": [{"runId": "a", "diverge": 0}, {"runId": "c", "diverge": 3},
                       {"runId": "d", "diverge": 0}]}
    assert bd.diverging_folders(agg) == ["c"]

def test_cmd_collect_pulls_merges_and_writes_triage(tmp_path, monkeypatch):
    work = tmp_path / "bo"; work.mkdir()
    (work / "dispatch-state.json").write_text(json.dumps({
        "remote_root": "~/scratch",
        "hosts": [{"host": "m2-1", "status": "running", "remote_dir": "~/scratch/m2-1"},
                  {"host": "m4-1", "status": "skipped-busy", "remote_dir": "~/scratch/m4-1"}],
    }))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)

    def fake_pull(creds, remote_dir, subdir, local_dir, runner=None):
        outdir = os.path.join(local_dir, "out"); os.makedirs(outdir, exist_ok=True)
        json.dump({"folders": [{"runId": "a", "status": "ok", "diverge": 2}],
                   "totalFolders": 1, "ok": 1, "incomplete": 0, "errors": 0},
                  open(os.path.join(outdir, "report.json"), "w"))
        return 1
    fake = type("T", (), {"pull_out_counted": staticmethod(fake_pull),
                          "remove_remote_scratch": staticmethod(lambda *a, **k: None)})
    args = bd._ns(work_dir=str(work), inventory=str(inv))
    agg = bd.cmd_collect(args, transport=fake)

    assert agg["totalFolders"] == 1
    assert os.path.exists(os.path.join(str(work), "corpus-report.json"))
    triage = open(os.path.join(str(work), "triage-folders.txt")).read().split()
    assert triage == ["a"]                                # only the diverging folder


def test_collect_emits_classification(tmp_path):
    # cmd_collect writes classification.json alongside corpus-report.json using
    # the SAME core function as run_differential — identical semantics.
    work = tmp_path / "batch-out"
    (work / "out" / "run_wahed").mkdir(parents=True)
    with open(work / "out" / "run_wahed" / "diff.json", "w") as fh:
        json.dump({"steps": [
            {"stepIndex": 1, "command": "TapOnElementCommand", "status": "DIVERGE",
             "errorType": None, "errorMessage": "not actionable"}]}, fh)
    agg = {"folders": [{"runId": "run_wahed", "package": "com.wahed", "status": "ok"}]}
    classification.write_classification(
        str(work / "out"), agg, str(work / "classification.json"))
    data = json.load(open(work / "classification.json"))
    assert data["runs"][0]["bucket"] == "genuine-fidelity"


def test_cmd_collect_flattens_and_emits_classification(tmp_path):
    # cmd_collect pulls each host into <work>/<host>/out/, flattens per-run dirs
    # into a shared <work>/out/, and emits classification.json from that flat
    # tree — the same layout run_differential.main writes, so semantics match.
    work = tmp_path / "bo"; work.mkdir()
    (work / "dispatch-state.json").write_text(json.dumps({
        "remote_root": "~/scratch",
        "hosts": [{"host": "m2-1", "status": "running", "remote_dir": "~/scratch/m2-1"}],
    }))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)

    def fake_pull(creds, remote_dir, subdir, local_dir, runner=None):
        outdir = os.path.join(local_dir, "out")
        run_dir = os.path.join(outdir, "run_wahed")
        os.makedirs(run_dir, exist_ok=True)
        json.dump({"steps": [
            {"stepIndex": 1, "command": "TapOnElementCommand", "status": "DIVERGE",
             "errorType": None, "errorMessage": "not actionable"}]},
            open(os.path.join(run_dir, "diff.json"), "w"))
        json.dump({"folders": [{"runId": "run_wahed", "package": "com.wahed",
                                "status": "ok", "diverge": 1}],
                   "totalFolders": 1, "ok": 1, "incomplete": 0, "errors": 0},
                  open(os.path.join(outdir, "report.json"), "w"))
        return 1
    fake = type("T", (), {"pull_out_counted": staticmethod(fake_pull)})
    args = bd._ns(work_dir=str(work), inventory=str(inv))
    bd.cmd_collect(args, transport=fake)

    # flattened tree exists at <work>/out/<runId>/diff.json
    assert os.path.isfile(os.path.join(str(work), "out", "run_wahed", "diff.json"))
    data = json.load(open(os.path.join(str(work), "classification.json")))
    assert data["runs"][0]["runId"] == "run_wahed"
    assert data["runs"][0]["bucket"] == "genuine-fidelity"


# --- remote self-cleanup: /tmp scratch default + post-collect teardown ---

def test_default_remote_root_is_ephemeral_and_under_tmp():
    # The name itself must read as "safe to delete" — under /tmp, clearly labeled.
    root = bd.DEFAULTS["remote_root"]
    assert root.startswith("/tmp/")
    assert "maestro-differential" in root
    assert not root.startswith("~")                       # no longer under $HOME


class _CleanupTransport:
    """Records pulls + scratch removals; pull writes a canned report per host."""
    def __init__(self, report=None, pull_raises=False):
        self.removed = []
        self.report = report or {"folders": [{"runId": "a", "status": "ok", "diverge": 2}],
                                 "totalFolders": 1, "ok": 1, "incomplete": 0, "errors": 0}
        self.pull_raises = pull_raises
    def pull_out_counted(self, creds, remote_dir, subdir, local_dir, runner=None):
        if self.pull_raises:
            raise RuntimeError("truncated pull")
        outdir = os.path.join(local_dir, "out"); os.makedirs(outdir, exist_ok=True)
        json.dump(self.report, open(os.path.join(outdir, "report.json"), "w"))
        return self.report["totalFolders"]
    def remove_remote_scratch(self, creds, remote_dir, runner=None):
        self.removed.append(remote_dir)


def _collect_setup(tmp_path):
    work = tmp_path / "bo"; work.mkdir()
    (work / "dispatch-state.json").write_text(json.dumps({
        "remote_root": "/tmp/maestro-differential",
        "hosts": [{"host": "m2-1", "status": "running", "remote_dir": "/tmp/maestro-differential/m2-1"},
                  {"host": "m4-1", "status": "skipped-busy", "remote_dir": "/tmp/maestro-differential/m4-1"}],
    }))
    inv = tmp_path / "testing.yml"; inv.write_text(INV_FIX)
    return work, inv


def test_cmd_collect_removes_remote_scratch_after_verified_pull(tmp_path):
    work, inv = _collect_setup(tmp_path)
    t = _CleanupTransport()
    args = bd._ns(work_dir=str(work), inventory=str(inv), keep_remote=False)
    bd.cmd_collect(args, transport=t)
    # only the running host's tree is removed; the skipped-busy host is untouched
    assert t.removed == ["/tmp/maestro-differential/m2-1"]


def test_cmd_collect_keep_remote_preserves_scratch(tmp_path):
    work, inv = _collect_setup(tmp_path)
    t = _CleanupTransport()
    args = bd._ns(work_dir=str(work), inventory=str(inv), keep_remote=True)
    bd.cmd_collect(args, transport=t)
    assert t.removed == []                                # --keep-remote leaves it


def test_cmd_collect_cleans_up_even_when_the_run_reported_errors(tmp_path):
    # A crashed remote run still touches DONE and leaves a pullable out/. Cleanup
    # keys off a successful PULL, not run success — so the host is still cleaned.
    work, inv = _collect_setup(tmp_path)
    t = _CleanupTransport(report={"folders": [{"runId": "a", "status": "error", "diverge": 0}],
                                  "totalFolders": 1, "ok": 0, "incomplete": 0, "errors": 1})
    args = bd._ns(work_dir=str(work), inventory=str(inv), keep_remote=False)
    bd.cmd_collect(args, transport=t)
    assert t.removed == ["/tmp/maestro-differential/m2-1"]


def test_cmd_collect_keeps_scratch_when_pull_fails(tmp_path):
    # A truncated pull must NOT trigger a delete — the un-collected data stays put.
    work, inv = _collect_setup(tmp_path)
    t = _CleanupTransport(pull_raises=True)
    args = bd._ns(work_dir=str(work), inventory=str(inv), keep_remote=False)
    with pytest.raises(RuntimeError):
        bd.cmd_collect(args, transport=t)
    assert t.removed == []                                # nothing removed on a failed pull


def test_dispatch_keep_remote_threads_keep_scratch_to_the_run(tmp_path):
    work, inv = _write_manifests(tmp_path)
    t = FakeTransport(idle=True)
    args = bd._ns(work_dir=work, inventory=inv, smoke=True,
                  remote_root="/tmp/maestro-differential", keep_remote=True)
    bd.cmd_dispatch(args, transport=t)
    assert all(rs["keep_scratch"] is True for rs in t.run_scripts)


def test_dispatch_defaults_to_self_cleaning_run(tmp_path):
    work, inv = _write_manifests(tmp_path)
    t = FakeTransport(idle=True)
    args = bd._ns(work_dir=work, inventory=inv, smoke=True,
                  remote_root="/tmp/maestro-differential", keep_remote=False)
    bd.cmd_dispatch(args, transport=t)
    assert all(rs["keep_scratch"] is False for rs in t.run_scripts)
