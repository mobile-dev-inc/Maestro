# validation-harness/inventory.py
"""inventory.py — pure parse of the copilot macStadium testing.yml.

Ports the run-time credential parse from the device-hosts run_remote.sh
heredoc into a testable function, and adds group enumeration so named hosts
can be validated against ios_agents / android_agents. Credentials are read
here and never persisted — the caller passes the password straight to sshpass.
Stdlib only.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

_HOST_LINE = re.compile(r"^(\s*)([\w.-]+):\s*$")
_FIELDS = {
    "ip": re.compile(r'ansible_host:\s*"?([^"\s]+)'),
    "user": re.compile(r'ansible_user:\s*"?([^"\s]+)'),
    "pw": re.compile(r'ansible_password:\s*"(.*)"\s*$|ansible_password:\s*([^"\s]+)\s*$'),
}


@dataclass
class HostCreds:
    host: str
    ip: str
    user: str
    password: str


def parse_host_creds(inventory_text: str, host: str) -> HostCreds:
    ip = user = pw = None
    in_host = False
    indent0 = 0
    for line in inventory_text.splitlines():
        m = _HOST_LINE.match(line)
        if m:
            name, indent = m.group(2), len(m.group(1))
            if name == host:
                in_host, indent0 = True, indent
                continue
            if in_host and indent <= indent0:
                break  # left the host block
            continue
        if in_host:
            mm = _FIELDS["ip"].search(line)
            if mm:
                ip = mm.group(1)
            mm = _FIELDS["user"].search(line)
            if mm:
                user = mm.group(1)
            mm = _FIELDS["pw"].search(line)
            if mm:
                pw = mm.group(1) if mm.group(1) is not None else mm.group(2)
    if not in_host:
        raise KeyError(f"host not found in inventory: {host!r}")
    if not (ip and user and pw is not None):
        raise ValueError(f"incomplete creds for {host!r}: ip={ip} user={user} pw={'set' if pw else None}")
    return HostCreds(host=host, ip=ip, user=user, password=pw)


def list_group_hosts(inventory_text: str, group: str) -> list[str]:
    hosts: list[str] = []
    in_group = False
    group_indent = 0
    in_hosts = False
    hosts_indent = 0
    for line in inventory_text.splitlines():
        m = _HOST_LINE.match(line)
        if not m:
            continue
        name, indent = m.group(2), len(m.group(1))
        if name == group:
            in_group, group_indent = True, indent
            in_hosts = False
            continue
        if in_group and indent <= group_indent:
            break  # left the group block
        if in_group and name == "hosts" and not in_hosts:
            in_hosts, hosts_indent = True, indent
            continue
        if in_hosts:
            if indent <= hosts_indent:
                in_hosts = False  # left the hosts mapping (e.g. a `vars:` sibling)
                continue
            # direct children of `hosts:` are host names; skip deeper var lines
            if indent == hosts_indent + _child_step(hosts_indent):
                hosts.append(name)
    return hosts


def _child_step(_hosts_indent: int) -> int:
    # Standard 2-space YAML indent. Host-name keys sit exactly one step under `hosts:`.
    return 2


def validate_named_hosts(inventory_text: str, ios_hosts: list[str], android_hosts: list[str]) -> None:
    ios_known = set(list_group_hosts(inventory_text, "ios_agents"))
    android_known = set(list_group_hosts(inventory_text, "android_agents"))
    bad = []
    for h in ios_hosts:
        if h not in ios_known:
            bad.append(f"{h!r} is not in ios_agents")
    for h in android_hosts:
        if h not in android_known:
            bad.append(f"{h!r} is not in android_agents")
    if bad:
        raise ValueError("host assignment invalid: " + "; ".join(bad))
