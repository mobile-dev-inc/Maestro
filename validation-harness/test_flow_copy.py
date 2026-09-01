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
    (ws / "flows" / "sub.yaml").write_text("appId: com.x\n---\n- tapOn: Login\n")
    out = tmp_path / "out" / "run_x"; out.mkdir(parents=True)
    written = flow_copy.copy_flow_scrubbed(
        str(out), str(main_flow), str(ws), secrets=["sk-live-XYZ"])
    flow_dir = out / "flow"
    blob = "".join(open(p).read() for p in written)
    assert "sk-live-XYZ" not in blob            # exit-check 8: zero token hits
    assert any("sub.yaml" in p for p in written)  # subflow copied too
    assert flow_dir.exists()
