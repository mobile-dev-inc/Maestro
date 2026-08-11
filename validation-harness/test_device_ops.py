from device_ops import install_cmd, reset_cmd, video_start_cmd

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
