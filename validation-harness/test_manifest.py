# test_manifest.py
import os, subprocess, manifest


def test_effective_version_prefers_local_override(tmp_path):
    (tmp_path / "devicecore.version").write_text("0.1.0-ba529198f969\n")
    (tmp_path / "devicecore.version.local").write_text("0.1.0-deadbeefcafe\n")
    assert manifest.effective_devicecore_version(str(tmp_path)) == "0.1.0-deadbeefcafe"


def test_effective_version_falls_back_to_committed_pin(tmp_path):
    (tmp_path / "devicecore.version").write_text("0.1.0-ba529198f969\n")
    assert manifest.effective_devicecore_version(str(tmp_path)) == "0.1.0-ba529198f969"


def test_content_hash_is_deterministic_and_content_sensitive(tmp_path):
    a = tmp_path / "a"; (a / "bin").mkdir(parents=True)
    (a / "bin" / "x").write_text("hello")
    h1 = manifest.content_hash(str(a))
    h2 = manifest.content_hash(str(a))
    assert h1 == h2 and h1.startswith("sha256:")
    (a / "bin" / "x").write_text("hello2")
    assert manifest.content_hash(str(a)) != h1


def test_git_identity_uses_injected_runner():
    calls = []
    def runner(argv, **kw):
        calls.append(argv)
        class R:
            returncode = 0
            stdout = "abc123def456\n" if "rev-parse" in argv else ""
        return R()
    ident = manifest.git_identity("/repo/foo", runner=runner)
    assert ident == {"repo": "foo", "gitSha": "abc123def456", "dirty": False}


def test_build_manifest_shape_is_small_and_complete():
    m = manifest.build_manifest(
        binaries=[{"role": "3x", "repo": "maestro", "gitSha": "s", "dirty": False,
                   "deviceCoreVersion": "0.1.0-x", "contentHash": "sha256:h",
                   "buildTime": "2026-09-01T00:00:00Z"}],
        harness_sha="hsha", host="localhost", timestamp="2026-09-01T00:00:00Z",
        tol=2, corpus_src="~/maestro-replay-harness")
    assert m["binaries"][0]["deviceCoreVersion"] == "0.1.0-x"
    assert m["harnessSha"] == "hsha" and m["tol"] == 2
    import json; assert len(json.dumps(m)) < 4096   # ~1 KB budget, generous ceiling
