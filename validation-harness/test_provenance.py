# test_provenance.py
import json, os, provenance


def test_write_source_records_corpus_and_app_hash(tmp_path):
    class Spec:
        run_dir = "/corpus/run_x"; run_id = "run_x"
        app_binary = str(tmp_path / "app.apk")
    (tmp_path / "app.apk").write_text("apk-bytes")
    out = tmp_path / "out" / "run_x"; out.mkdir(parents=True)
    src = provenance.write_source(str(out), Spec(), upload_id="up-1")
    on_disk = json.load(open(out / "source.json"))
    assert on_disk == src
    assert src["corpusPath"] == "/corpus/run_x"
    assert src["uploadId"] == "up-1"
    assert src["appContentHash"].startswith("sha256:")


def test_write_provenance_references_binaries_by_hash(tmp_path):
    out = tmp_path / "out" / "run_x"; out.mkdir(parents=True)
    binaries = [{"role": "3x", "contentHash": "sha256:aaa"},
                {"role": "2x", "contentHash": "sha256:bbb"}]
    prov = provenance.write_provenance(str(out), binaries)
    on_disk = json.load(open(out / "provenance.json"))
    assert on_disk == prov
    assert {b["contentHash"] for b in prov["binaries"]} == {"sha256:aaa", "sha256:bbb"}
