import json
import device_ops
from device_ops import install_cmd, reset_cmd, video_start_cmd, ios_extract_app

def test_android_install():
    assert install_cmd("ANDROID", "emulator-5", "/tmp/app.apk") == \
        ["adb", "-s", "emulator-5", "install", "-r", "/tmp/app.apk"]

def test_ios_install():
    assert install_cmd("IOS", "UDID-1", "/tmp/App.app") == \
        ["xcrun", "simctl", "install", "UDID-1", "/tmp/App.app"]

def test_android_reset_is_pm_clear():
    assert reset_cmd("ANDROID", "emulator-5", "com.x") == "adb -s emulator-5 shell pm clear com.x"

def test_ios_reset_is_terminate():
    assert reset_cmd("IOS", "UDID-1", "com.x") == "xcrun simctl terminate UDID-1 com.x"

def test_android_video_start_is_screenrecord():
    s = video_start_cmd("ANDROID", "emulator-5", "/data/local/tmp/screen.mp4")
    assert "screenrecord" in s and "emulator-5" in s and "/data/local/tmp/screen.mp4" in s

def test_ios_video_start_is_simctl_recordvideo():
    s = video_start_cmd("IOS", "UDID-1", "/tmp/screen.mp4")
    assert "simctl" in s and "recordVideo" in s and "UDID-1" in s

def test_unknown_platform_raises():
    import pytest
    with pytest.raises(Exception):
        install_cmd("WEB", "x", "y")


class _FakeSh:
    """Fake executor whose `sh` returns a canned stdout for the find, regardless
    of layout (Payload/*.app vs a flat root .app like Airalo's app.ipa)."""
    def __init__(self, find_stdout):
        self._find_stdout = find_stdout
        self.calls = []

    def sh(self, script, timeout=None, check=True):
        self.calls.append(script)
        class R:
            pass
        r = R()
        r.stdout = self._find_stdout
        r.returncode = 0
        return r


def test_ios_extract_app_finds_flat_bundle_not_under_payload():
    # Real-world case (Airalo's app.ipa): the .app extracts to the archive root,
    # not under Payload/. The bounded find must still locate it.
    ex = _FakeSh("/w/Airalo.app\n")
    assert ios_extract_app(ex, "/tmp/app.ipa", "/w") == "/w/Airalo.app"
    assert any("find" in c and "-maxdepth 3" in c and "-name '*.app'" in c for c in ex.calls)


def test_ios_extract_app_raises_when_find_is_empty():
    import pytest
    ex = _FakeSh("")
    with pytest.raises(RuntimeError):
        ios_extract_app(ex, "/tmp/app.ipa", "/w")


# --- per-run sim clone cleanup: tiers, builders, parse (preserve goldens) ---

def test_golden_sim_name_is_recognised_and_sweepable_is_not():
    assert device_ops.is_golden_sim_name("GOLDEN_MAESTRO_iPhone-15_iOS-18-2_v7")
    assert not device_ops.is_golden_sim_name("RUNTIME_MAESTRO_ab12cd34")
    # the golden is NEVER sweepable; the per-run/scratch tiers ARE
    assert not device_ops.is_sweepable_sim_name("GOLDEN_MAESTRO_iPhone-15_iOS-18-2_v7")
    assert device_ops.is_sweepable_sim_name("RUNTIME_MAESTRO_ab12cd34")
    assert device_ops.is_sweepable_sim_name("SCRATCH_MAESTRO_iPhone-15_iOS-18-2_v7_ab12")
    assert device_ops.is_sweepable_sim_name("SCRATCH_VALIDATE_ab12cd34")
    # an unrelated/user device is neither golden nor sweepable — left alone
    assert not device_ops.is_sweepable_sim_name("iPhone 16 Pro")


def test_sim_delete_and_list_cmds_are_simctl():
    assert device_ops.sim_delete_cmd("UDID-1") == ["xcrun", "simctl", "delete", "UDID-1"]
    assert device_ops.sim_list_json_cmd() == ["xcrun", "simctl", "list", "devices", "-j"]


_SIM_LIST = json.dumps({
    "devices": {
        "com.apple.CoreSimulator.SimRuntime.iOS-18-2": [
            {"udid": "G-1", "name": "GOLDEN_MAESTRO_iPhone-15_iOS-18-2_v7", "state": "Shutdown"},
            {"udid": "R-1", "name": "RUNTIME_MAESTRO_ab12cd34", "state": "Shutdown"},
            {"udid": "R-2", "name": "RUNTIME_MAESTRO_ff99ff99", "state": "Booted"},
            {"udid": "S-1", "name": "SCRATCH_VALIDATE_dead", "state": "Shutdown"},
            {"udid": "U-1", "name": "iPhone 16 Pro", "state": "Shutdown"},
        ]
    }
})


def test_sim_name_for_udid_and_missing():
    assert device_ops.sim_name_for_udid(_SIM_LIST, "R-1") == "RUNTIME_MAESTRO_ab12cd34"
    assert device_ops.sim_name_for_udid(_SIM_LIST, "G-1").startswith("GOLDEN_MAESTRO_")
    assert device_ops.sim_name_for_udid(_SIM_LIST, "gone") is None


def test_sweepable_clone_udids_excludes_golden_booted_and_user_devices():
    got = set(device_ops.sweepable_clone_udids(_SIM_LIST))
    assert got == {"R-1", "S-1"}          # Shutdown clones only
    assert "G-1" not in got               # never a golden
    assert "R-2" not in got               # never a Booted (in-use) clone
    assert "U-1" not in got               # never an unrelated device


def test_sweepable_clone_udids_empty_on_blank():
    assert device_ops.sweepable_clone_udids("") == []
    assert device_ops.sweepable_clone_udids("{}") == []
