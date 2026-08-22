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
