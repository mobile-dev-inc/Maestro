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
