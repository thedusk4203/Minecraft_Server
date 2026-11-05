#!/usr/bin/env python3
"""Fail closed on exposed test-only offline Paper / ViaProxy listeners.

Static mode audits the resolved E2E Compose model and the pinned ViaProxy
launcher. Runtime mode checks the actual Linux listening socket on port 25568.
Resolved Docker Compose JSON contains credentials: NEVER print it or the
subprocess stderr.
"""
from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
PAPER_TARGET_PORT = 25565
PAPER_HOST_PORT = 25567
VIA_HOST_PORT = 25568
LOOPBACK_V4_HEX = "0100007F"  # /proc/net/tcp encodes 127.0.0.1 little-endian.
# Java/Netty may use an AF_INET6 socket with an IPv4-mapped loopback
# address. Linux /proc/net/tcp6 represents its 32-bit words little-endian:
# ::ffff:127.0.0.1 => 0000000000000000 FFFF0000 0100007F.
MAPPED_LOOPBACK_V6_HEX = "0000000000000000FFFF00000100007F"


def check_static(model: dict[str, Any], launcher: str) -> list[str]:
    """Use diagnostic identifiers only: never echo secrets or full config."""
    problems: list[str] = []
    services = model.get("services")
    if not isinstance(services, dict):
        return ["E2E_SERVICES_INVALID"]

    for name in ("postgres-e2e", "paper-e2e"):
        entry = services.get(name)
        if not isinstance(entry, dict):
            problems.append("E2E_MISSING_SERVICE:" + name)
            continue
        if entry.get("network_mode") is not None:
            problems.append("E2E_UNEXPECTED_NETWORK_MODE:" + name)
        if entry.get("privileged") is True:
            problems.append("E2E_PRIVILEGED_SERVICE:" + name)

    postgres = services.get("postgres-e2e") or {}
    paper = services.get("paper-e2e") or {}
    if postgres.get("ports"):
        problems.append("E2E_EXPOSED_POSTGRES")
    expected = [{"target": PAPER_TARGET_PORT, "published": str(PAPER_HOST_PORT),
                 "protocol": "tcp", "host_ip": "127.0.0.1"}]
    ports = paper.get("ports") or []
    if not isinstance(ports, list) or len(ports) != 1:
        problems.append("E2E_INCORRECT_PAPER_PORT_COUNT")
    else:
        port = ports[0]
        if not isinstance(port, dict) or any(port.get(k) != v for k, v in expected[0].items()):
            problems.append("E2E_PAPER_NOT_LOOPBACK_ONLY")
    # An unknown service on the test Compose network could publish another
    # unauthenticated port. A new service requires an explicit security review.
    unknown = set(services) - {"postgres-e2e", "paper-e2e"}
    if unknown:
        problems.append("E2E_UNREVIEWED_SERVICE")
    if "ONLINE_MODE" not in (paper.get("environment") or {}):
        problems.append("E2E_PAPER_MODE_MISSING")
    elif str(paper["environment"]["ONLINE_MODE"]).upper() != "FALSE":
        problems.append("E2E_PAPER_MODE_CHANGED")
    if "--bind-address 127.0.0.1:25568" not in launcher:
        problems.append("E2E_VIAPROXY_BIND_NOT_LOOPBACK")
    if "--target-address 127.0.0.1:25567" not in launcher:
        problems.append("E2E_VIAPROXY_TARGET_CHANGED")
    return problems


def tcp_listeners(table: str, port: int) -> list[str]:
    """Read /proc/net/tcp{,6} into opaque address tokens for specific port."""
    listeners = []
    lines = table.splitlines()
    for line in lines[1:]:
        fields = line.strip().split()
        if len(fields) < 4 or ":" not in fields[1]:
            continue
        address, encoded_port = fields[1].rsplit(":", 1)
        try:
            selected_port = int(encoded_port, 16)
        except ValueError:
            continue
        if selected_port == port and fields[3] == "0A":  # TCP_LISTEN
            listeners.append(address.upper())
    return listeners


def check_runtime(tcp4: str, tcp6: str) -> list[str]:
    ipv4 = tcp_listeners(tcp4, VIA_HOST_PORT)
    ipv6 = tcp_listeners(tcp6, VIA_HOST_PORT)
    # Require exactly ONE bound listener. AF_INET6 with an IPv4-mapped
    # 127.0.0.1 address is equivalent to a loopback-only IPv4 listener;
    # it is NOT a wildcard IPv6 bind and must not be confused with ::.
    if (ipv4 == [LOOPBACK_V4_HEX] and not ipv6) or (
            not ipv4 and ipv6 == [MAPPED_LOOPBACK_V6_HEX]):
        return []
    return ["E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER"]


def _fixture() -> tuple[dict[str, Any], str]:
    return {
        "services": {
            "postgres-e2e": {},
            "paper-e2e": {
                "environment": {"ONLINE_MODE": "FALSE"},
                "ports": [{"target": 25565, "published": "25567",
                           "protocol": "tcp", "host_ip": "127.0.0.1"}],
            },
        },
    }, "--bind-address 127.0.0.1:25568 --target-address 127.0.0.1:25567"


def _table(ip: str, port: int = VIA_HOST_PORT, state: str = "0A") -> str:
    return ("  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
            f"   0: {ip}:{port:04X} 00000000:0000 {state} 00000000:00000000 00:00000000 00000000 1000 0 0\n")


class E2ELoopbackTests(unittest.TestCase):
    def test_valid_static_fixture(self) -> None:
        self.assertEqual(check_static(*_fixture()), [])

    def _mutate(self, op, expected: str) -> None:
        model, launcher = copy.deepcopy(_fixture())
        model, launcher = op(model, launcher)
        self.assertIn(expected, check_static(model, launcher))

    def test_wildcard_paper_publication(self) -> None:
        self._mutate(lambda m, l: (
            self._set_port(m, "0.0.0.0"), l
        ), "E2E_PAPER_NOT_LOOPBACK_ONLY")

    def test_unqualified_paper_host_publication(self) -> None:
        def modify(m, l):
            m["services"]["paper-e2e"]["ports"][0].pop("host_ip")
            return m, l
        self._mutate(modify, "E2E_PAPER_NOT_LOOPBACK_ONLY")

    @staticmethod
    def _set_port(model, host):
        model["services"]["paper-e2e"]["ports"][0]["host_ip"] = host
        return model

    def test_additional_paper_port(self) -> None:
        def modify(m, l):
            m["services"]["paper-e2e"]["ports"].append(
                copy.deepcopy(m["services"]["paper-e2e"]["ports"][0]))
            return m, l
        self._mutate(modify, "E2E_INCORRECT_PAPER_PORT_COUNT")

    def test_paper_wrong_host_port(self) -> None:
        def modify(m, l):
            m["services"]["paper-e2e"]["ports"][0]["published"] = "25565"
            return m, l
        self._mutate(modify, "E2E_PAPER_NOT_LOOPBACK_ONLY")

    def test_public_postgres(self) -> None:
        def modify(m, l):
            m["services"]["postgres-e2e"]["ports"] = [{
                "target": 5432, "published": "5432", "protocol": "tcp"}]
            return m, l
        self._mutate(modify, "E2E_EXPOSED_POSTGRES")

    def test_paper_host_network(self) -> None:
        def modify(m, l):
            m["services"]["paper-e2e"]["network_mode"] = "host"
            return m, l
        self._mutate(modify, "E2E_UNEXPECTED_NETWORK_MODE:paper-e2e")

    def test_new_unreviewed_service(self) -> None:
        def modify(m, l):
            m["services"]["unexpected"] = {}
            return m, l
        self._mutate(modify, "E2E_UNREVIEWED_SERVICE")

    def test_launcher_wildcard_bind(self) -> None:
        self._mutate(lambda m, l: (
            m, l.replace("--bind-address 127.0.0.1:25568",
                         "--bind-address 0.0.0.0:25568")
        ), "E2E_VIAPROXY_BIND_NOT_LOOPBACK")

    def test_launcher_target_changed(self) -> None:
        self._mutate(lambda m, l: (
            m, l.replace("--target-address 127.0.0.1:25567",
                         "--target-address 0.0.0.0:25567")
        ), "E2E_VIAPROXY_TARGET_CHANGED")

    def test_valid_ipv4_runtime_listener(self) -> None:
        self.assertEqual(check_runtime(_table(LOOPBACK_V4_HEX), _table("0" * 32, state="01")), [])

    def test_valid_ipv4_mapped_ipv6_runtime_listener(self) -> None:
        self.assertEqual(check_runtime("", _table(MAPPED_LOOPBACK_V6_HEX)), [])

    def test_runtime_wildcard_ipv4_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime(_table("00000000"), ""))

    def test_runtime_wildcard_ipv6_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime("", _table("0" * 32)))

    def test_runtime_unmapped_ipv6_loopback_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime("", _table("00000000000000000000000001000000")))

    def test_runtime_no_listener_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime("", ""))

    def test_runtime_duplicate_listener_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime(_table(LOOPBACK_V4_HEX) * 2, ""))

    def test_runtime_ipv4_and_mapped_ipv6_double_bind_rejected(self) -> None:
        self.assertIn("E2E_VIAPROXY_NOT_EXACTLY_ONE_LOOPBACK_LISTENER",
                      check_runtime(_table(LOOPBACK_V4_HEX), _table(MAPPED_LOOPBACK_V6_HEX)))


def inspect_static() -> int:
    try:
        process = subprocess.run(
            ["docker", "compose", "-f", "compose.e2e.yaml", "config", "--format", "json"],
            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            check=True, timeout=45,
        )
        model = json.loads(process.stdout)
        launcher = (ROOT / "tools/e2e/start-viaproxy.sh").read_text("utf-8")
        issues = check_static(model, launcher)
    except (OSError, ValueError, subprocess.SubprocessError):
        print("E2E_LOOPBACK_STATIC_FAIL cannot-read-test-config")
        return 2
    if issues:
        for item in sorted(set(issues)):
            print("E2E_LOOPBACK_STATIC_FAIL " + item)
        return 1
    print("E2E_LOOPBACK_STATIC_PASS")
    return 0


def inspect_runtime() -> int:
    if sys.platform != "linux":
        print("E2E_LOOPBACK_RUNTIME_FAIL linux-required")
        return 2
    try:
        tcp4 = Path("/proc/net/tcp").read_text("ascii")
        tcp6 = Path("/proc/net/tcp6").read_text("ascii")
    except OSError:
        print("E2E_LOOPBACK_RUNTIME_FAIL cannot-read-listeners")
        return 2
    issues = check_runtime(tcp4, tcp6)
    if issues:
        for item in sorted(set(issues)):
            print("E2E_LOOPBACK_RUNTIME_FAIL " + item)
        return 1
    print("E2E_LOOPBACK_RUNTIME_PASS")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify synthetic E2E listeners are localhost only")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--self-test", action="store_true")
    modes.add_argument("--runtime", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[sys.argv[0]], verbosity=2)
    sys.exit(inspect_runtime() if args.runtime else inspect_static())
