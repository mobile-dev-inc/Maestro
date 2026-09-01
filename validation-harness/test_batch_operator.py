# test_batch_operator.py
import batch_operator, batch_differential as bd


def test_operator_owns_partition_and_smoke_selection():
    # The pool/partition concerns live in the leaf; transport does not import them.
    assert hasattr(batch_operator, "cmd_partition")
    assert hasattr(batch_operator, "smoke_selection")
    assert hasattr(batch_operator, "claim_host")


def test_smoke_selection_still_picks_one_of_each():
    manifest = {
        "ios-1": {"platform": "IOS", "folders": ["/f/ios"]},
        "and-1": {"platform": "ANDROID", "folders": ["/f/and"]},
    }
    sel = batch_operator.smoke_selection(manifest)
    plats = {e["platform"] for e in sel.values()}
    assert plats == {"IOS", "ANDROID"}
    assert all(len(e["folders"]) == 1 for e in sel.values())


def test_batch_differential_reexports_the_leaf():
    # Existing imports/tests use bd.<fn>; re-export keeps them the SAME object.
    assert bd.cmd_partition is batch_operator.cmd_partition
    assert bd.smoke_selection is batch_operator.smoke_selection
    assert bd.claim_host is batch_operator.claim_host
