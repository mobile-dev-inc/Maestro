# test_flow_copy.py
import os, flow_copy


def test_scrub_removes_secret_values():
    text = "appId: com.x\n---\n- inputText: ${API_TOKEN}\n- runScript: token=sk-live-abc123\n"
    out = flow_copy.scrub_flow(text, secrets=["sk-live-abc123"])
    assert "sk-live-abc123" not in out
    assert "***REDACTED***" in out


def test_copy_flow_scrubbed_writes_flow_dir_without_secrets(tmp_path):
    ws = tmp_path / "workspace"; (ws / "flows").mkdir(parents=True)
    main_flow = ws / "flows" / "f.yaml"
    main_flow.write_text("appId: com.x\n---\n- runFlow: sub.yaml\n- inputText: sk-live-XYZ\n")
    # Put a DISTINCT secret INTO the subflow so subflow scrubbing is proven.
    (ws / "flows" / "sub.yaml").write_text(
        "appId: com.x\n---\n- inputText: sk-sub-SECRET\n- tapOn: Login\n")
    out = tmp_path / "out" / "run_x"; out.mkdir(parents=True)
    written = flow_copy.copy_flow_scrubbed(
        str(out), str(main_flow), str(ws), secrets=["sk-live-XYZ", "sk-sub-SECRET"])
    flow_dir = out / "flow"
    blob = "".join(open(p).read() for p in written)
    assert "sk-live-XYZ" not in blob            # exit-check 8: zero token hits
    assert "sk-sub-SECRET" not in blob          # subflow scrubbed too
    # and specifically the copied subflow file itself carries no secret
    sub_copy = next(p for p in written if "sub.yaml" in p)
    assert "sk-sub-SECRET" not in open(sub_copy).read()
    assert any("sub.yaml" in p for p in written)  # subflow copied too
    assert flow_dir.exists()


def test_scrub_tolerates_non_string_and_empty_secret_values():
    # Corpus metadata.json env values can be JSON numbers/booleans/null.
    # A non-string truthy value must not crash the replace; empty/None skipped.
    text = "appId: com.x\n---\n- inputText: ${API_TOKEN}\n- runScript: n=5\n"
    out = flow_copy.scrub_flow(text, secrets=[5, True, "", None, "sk-live-abc"])
    assert "sk-live-abc" not in out
    assert "***REDACTED***" in out           # the string secret was redacted
    # numeric secret stringified and redacted where it appears
    assert "n=***REDACTED***" in out


def test_block_form_runflow_is_collected_and_scrubbed(tmp_path):
    ws = tmp_path / "workspace"; (ws / "flows").mkdir(parents=True)
    main_flow = ws / "flows" / "f.yaml"
    # Block form: `- runFlow:` then an indented `file:` (with a sibling env:).
    main_flow.write_text(
        "appId: com.x\n---\n"
        "- runFlow:\n"
        "    file: block_sub.yaml\n"
        "    env:\n"
        "      FOO: bar\n"
        "- tapOn: Next\n")
    (ws / "flows" / "block_sub.yaml").write_text(
        "appId: com.x\n---\n- inputText: sk-block-SECRET\n")
    out = tmp_path / "out" / "run_b"; out.mkdir(parents=True)
    written = flow_copy.copy_flow_scrubbed(
        str(out), str(main_flow), str(ws), secrets=["sk-block-SECRET"])
    assert any("block_sub.yaml" in p for p in written)   # block-form collected
    sub_copy = next(p for p in written if "block_sub.yaml" in p)
    assert "sk-block-SECRET" not in open(sub_copy).read()  # and scrubbed
