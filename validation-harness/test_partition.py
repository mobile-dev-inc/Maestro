# validation-harness/test_partition.py
import json
import pytest
import partition
from partition import classify_platform, folder_platform, partition as split


def test_classify_platform_uppercases():
    assert classify_platform({"platform": "android"}) == "ANDROID"
    assert classify_platform({"platform": "IOS"}) == "IOS"


def test_classify_platform_rejects_unknown():
    with pytest.raises(ValueError):
        classify_platform({"platform": "web"})
    with pytest.raises(ValueError):
        classify_platform({})


def test_folder_platform_reads_metadata(tmp_path):
    d = tmp_path / "run_x"
    d.mkdir()
    (d / "metadata.json").write_text(json.dumps({"platform": "IOS", "device_spec": {"OS": "ios-18-2"}}))
    assert folder_platform(str(d)) == "IOS"


def test_partition_round_robins_per_platform():
    classified = [
        ("a1", "ANDROID"), ("a2", "ANDROID"), ("a3", "ANDROID"), ("a4", "ANDROID"),
        ("i1", "IOS"), ("i2", "IOS"), ("i3", "IOS"),
    ]
    out = split(classified, ios_hosts=["m4-1", "m4-2"], android_hosts=["m2-1", "m2-2"])
    # every host present, even split, no cross-platform leakage
    assert set(out) == {"m4-1", "m4-2", "m2-1", "m2-2"}
    assert out["m2-1"] == ["a1", "a3"]
    assert out["m2-2"] == ["a2", "a4"]
    assert out["m4-1"] == ["i1", "i3"]
    assert out["m4-2"] == ["i2"]


def test_partition_empty_host_gets_empty_list():
    out = split([("a1", "ANDROID")], ios_hosts=["m4-1"], android_hosts=["m2-1", "m2-2"])
    assert out["m2-2"] == [] and out["m4-1"] == []


def test_partition_folders_but_no_hosts_raises():
    with pytest.raises(ValueError):
        split([("i1", "IOS")], ios_hosts=[], android_hosts=["m2-1"])
