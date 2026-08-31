# validation-harness/test_inventory.py
import pytest
import inventory
from inventory import parse_host_creds, list_group_hosts, validate_named_hosts, HostCreds

INV = """\
all:
  children:
    ios_agents:
      hosts:
        arm-m4s-1:
          ansible_host: "10.0.0.11"
          ansible_user: "admin"
          ansible_password: "s3cr3t one"
        arm-m4s-2:
          ansible_host: 10.0.0.12
          ansible_user: admin
          ansible_password: "pw2"
    android_agents:
      hosts:
        arm-m2m-1:
          ansible_host: "10.0.0.21"
          ansible_user: "admin"
          ansible_password: "pw3"
"""

def test_parse_host_creds_reads_all_three_fields():
    c = parse_host_creds(INV, "arm-m4s-1")
    assert c == HostCreds(host="arm-m4s-1", ip="10.0.0.11", user="admin", password="s3cr3t one")

def test_parse_host_creds_handles_unquoted_values():
    c = parse_host_creds(INV, "arm-m4s-2")
    assert c.ip == "10.0.0.12" and c.user == "admin" and c.password == "pw2"

def test_parse_host_creds_missing_host_raises():
    with pytest.raises(KeyError):
        parse_host_creds(INV, "arm-m4s-99")

def test_parse_host_creds_stops_at_next_host_block():
    # arm-m4s-1's creds must not bleed into arm-m4s-2's block.
    assert parse_host_creds(INV, "arm-m4s-1").password == "s3cr3t one"

def test_list_group_hosts():
    assert list_group_hosts(INV, "ios_agents") == ["arm-m4s-1", "arm-m4s-2"]
    assert list_group_hosts(INV, "android_agents") == ["arm-m2m-1"]
    assert list_group_hosts(INV, "nope") == []

def test_validate_named_hosts_ok():
    validate_named_hosts(INV, ["arm-m4s-1"], ["arm-m2m-1"])  # no raise

def test_validate_named_hosts_rejects_cross_group():
    with pytest.raises(ValueError):
        validate_named_hosts(INV, ["arm-m2m-1"], ["arm-m2m-1"])  # android host named as iOS
