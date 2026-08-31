# validation-harness/test_batch_differential.py
import json, os
import pytest
import batch_differential as bd

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
    args = bd._ns(work_dir=str(tmp_path/"bo"), device_dir=dev, cli_2x_dir=c2, cli_3x_dir=c3)
    manifest = bd.cmd_build(args, runner=RecRunner())
    written = json.load(open(os.path.join(str(tmp_path/"bo"), "build-manifest.json")))
    assert written == manifest
    assert set(manifest) == {"device_bin", "cli_2x", "cli_3x"}


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
