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


def _build_tree(root):
    files = [
        ("zeta/b.txt", "bbb"),
        ("alpha/a.txt", "aaa"),
        ("alpha/nested/c.txt", "ccc"),
        ("mid/d.txt", "ddd"),
        ("zeta/nested/e.txt", "eee"),
    ]
    for rel, content in files:
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def test_content_hash_is_independent_of_on_disk_dir_order(tmp_path, monkeypatch):
    # os.walk yields subdirectories in filesystem order. content_hash keeps a
    # single running digest whose value depends on the order roots are visited,
    # so unless it sorts the dirs list the same tree hashes differently across
    # machines/checkouts. This forces two different walk orderings for the same
    # tree (real filesystem order can be stable on a given FS, e.g. APFS, which
    # would mask the bug) and asserts the hash is identical either way.
    tree = tmp_path / "tree"
    _build_tree(tree)

    real_walk = os.walk

    def walk_with(reorder):
        def _walk(top, *a, **kw):
            for root, dirs, files in real_walk(top, *a, **kw):
                reorder(dirs)          # perturb dir order before caller sees it
                yield root, dirs, files
        return _walk

    monkeypatch.setattr(manifest.os, "walk", walk_with(lambda d: d.sort()))
    h_sorted = manifest.content_hash(str(tree))
    monkeypatch.setattr(manifest.os, "walk", walk_with(lambda d: d.sort(reverse=True)))
    h_reversed = manifest.content_hash(str(tree))
    monkeypatch.undo()

    assert h_sorted == h_reversed
    # and stable when re-hashed
    assert manifest.content_hash(str(tree)) == manifest.content_hash(str(tree))


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


def test_vendor_bins_dedupes_by_hash(tmp_path):
    src1 = tmp_path / "t1"; (src1).mkdir(); (src1 / "f").write_text("same")
    src2 = tmp_path / "t2"; (src2).mkdir(); (src2 / "f").write_text("same")   # identical
    src3 = tmp_path / "t3"; (src3).mkdir(); (src3 / "f").write_text("diff")
    dest = tmp_path / "bin"
    mapping = manifest.vendor_bins(
        {"2x": str(src1), "3x": str(src2), "device": str(src3)}, str(dest))
    assert mapping["2x"] == mapping["3x"] != mapping["device"]
    # exactly two distinct hash dirs materialized (identical trees deduped)
    assert len(list(dest.iterdir())) == 2


def test_no_vendor_bins_writes_nothing(tmp_path):
    dest = tmp_path / "bin"
    manifest.vendor_bins({}, str(dest))
    assert not dest.exists() or not any(dest.iterdir())
