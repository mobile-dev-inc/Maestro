import json, os
import pytest
from run_folder import read_run_folder, expand_folders

def _make_folder(tmp_path, platform, app_name, name="run_abc"):
    d = tmp_path / name
    (d / "workspace" / "flows").mkdir(parents=True)
    (d / "workspace" / "flows" / "f.yaml").write_text("appId: com.x\n---\n- launchApp\n")
    (d / app_name).write_text("binary")
    (d / "metadata.json").write_text(json.dumps({
        "run_id": name, "platform": platform, "package_id": "com.x",
        "device_spec": {"model": "pixel_6", "os": "android-34", "locale": None},
        "env": {"K": "V"}, "flow_file_path": "flows/f.yaml",
    }))
    return str(d)

def test_reads_android_folder(tmp_path):
    spec = read_run_folder(_make_folder(tmp_path, "ANDROID", "app.apk"))
    assert spec.platform == "ANDROID"
    assert spec.package_id == "com.x"
    assert spec.app_binary.endswith("app.apk")
    assert spec.flow_file.endswith(os.path.join("workspace", "flows", "f.yaml"))
    assert spec.env == {"K": "V"}
    assert spec.device_spec["model"] == "pixel_6"
    assert spec.run_id == "run_abc"

def test_reads_ios_folder(tmp_path):
    spec = read_run_folder(_make_folder(tmp_path, "IOS", "app.ipa"))
    assert spec.platform == "IOS"
    assert spec.app_binary.endswith("app.ipa")

def test_missing_app_binary_yields_no_install_spec(tmp_path):
    # Fix 2 (finding #2): a folder with no app binary on disk is a valid,
    # folder-less/no-install built-in-app flow (e.g. com.android.settings) —
    # not an error. read_run_folder must accept it and set app_binary=None
    # so run_differential.py knows to skip the install step entirely.
    d = _make_folder(tmp_path, "ANDROID", "app.apk")
    os.remove(os.path.join(d, "app.apk"))
    spec = read_run_folder(d)
    assert spec.app_binary is None

def test_missing_flow_raises(tmp_path):
    d = _make_folder(tmp_path, "ANDROID", "app.apk")
    os.remove(os.path.join(d, "workspace", "flows", "f.yaml"))
    with pytest.raises(Exception):
        read_run_folder(d)

def test_bad_platform_raises(tmp_path):
    d = tmp_path / "run_bad"
    (d / "workspace").mkdir(parents=True)
    (d / "app.apk").write_text("x")
    (d / "metadata.json").write_text(json.dumps({
        "platform": "WEB", "package_id": "com.x",
        "device_spec": {}, "env": {}, "flow_file_path": "f.yaml"}))
    with pytest.raises(ValueError):
        read_run_folder(str(d))

def test_expand_folders(tmp_path):
    a = _make_folder(tmp_path, "ANDROID", "app.apk", name="run_a")
    b = _make_folder(tmp_path, "IOS", "app.ipa", name="run_b")
    (tmp_path / "not_a_run").mkdir()
    found = expand_folders([str(tmp_path / "run_*")])
    assert set(found) == {a, b}
