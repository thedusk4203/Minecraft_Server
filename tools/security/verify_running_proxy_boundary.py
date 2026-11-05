#!/usr/bin/env python3
"""Verify the *running* DEV Compose proxy/backend boundary without leaking secrets.

Scope: the GitHub CI development stack, not a remotely deployed firewall or
proof that a Java/Bedrock account authenticated. Docker inspect output can
contain database, RCON and forwarding secrets and MUST NOT be printed.
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
SERVICES = ("velocity", "paper", "postgres")
EXPECTED_PORTS = {"25565/tcp": "25565", "19132/tcp": "19132", "19132/udp": "19132"}


def _env(container: dict[str, Any]) -> dict[str, str]:
    items = (container.get("Config") or {}).get("Env") or []
    if not isinstance(items, list):
        return {}
    output = {}
    for item in items:
        if isinstance(item, str) and "=" in item:
            name, value = item.split("=", 1)
            output[name] = value
    return output


def _mapped_ports(container: dict[str, Any], where: str) -> dict[str, Any]:
    if where == "host":
        return (container.get("HostConfig") or {}).get("PortBindings") or {}
    return (container.get("NetworkSettings") or {}).get("Ports") or {}


def audit_running(containers: dict[str, dict[str, Any]]) -> list[str]:
    errors: list[str] = []
    networks: dict[str, set[str]] = {}
    projects: set[str] = set()
    for service in SERVICES:
        container = containers.get(service)
        if not isinstance(container, dict):
            errors.append("RUNTIME_MISSING_SERVICE:" + service)
            continue
        state = container.get("State") or {}
        host = container.get("HostConfig") or {}
        cfg = container.get("Config") or {}
        net = container.get("NetworkSettings") or {}
        labels = cfg.get("Labels") or {}

        if state.get("Running") is not True:
            errors.append("RUNTIME_NOT_RUNNING:" + service)
        if labels.get("com.docker.compose.service") != service:
            errors.append("RUNTIME_SERVICE_LABEL_MISMATCH:" + service)
        project = labels.get("com.docker.compose.project")
        if not isinstance(project, str) or not project:
            errors.append("RUNTIME_MISSING_PROJECT_LABEL:" + service)
        else:
            projects.add(project)
        if host.get("Privileged") is True:
            errors.append("RUNTIME_PRIVILEGED_CONTAINER:" + service)
        mode = host.get("NetworkMode")
        networks_value = net.get("Networks") or {}
        network_names = set(networks_value) if isinstance(networks_value, dict) else set()
        networks[service] = network_names
        if len(network_names) != 1 or mode not in network_names:
            errors.append("RUNTIME_UNEXPECTED_NETWORK_MODE:" + service)

        host_ports = _mapped_ports(container, "host")
        actual_ports = _mapped_ports(container, "runtime")
        if not isinstance(host_ports, dict) or not isinstance(actual_ports, dict):
            errors.append("RUNTIME_INVALID_PORT_MODEL:" + service)
            continue

        if service != "velocity":
            if any(bindings for bindings in host_ports.values()):
                errors.append("RUNTIME_PUBLISHED_BACKEND_PORT:" + service)
            if any(bindings for bindings in actual_ports.values()):
                errors.append("RUNTIME_ACTIVE_BACKEND_PORT:" + service)
            continue

        if set(host_ports) != set(EXPECTED_PORTS):
            errors.append("RUNTIME_PROXY_PORT_SET_MISMATCH")
        for port, expected_host_port in EXPECTED_PORTS.items():
            binds = host_ports.get(port)
            if not isinstance(binds, list) or not binds:
                errors.append("RUNTIME_MISSING_PROXY_PORT:" + port)
                continue
            for binding in binds:
                if not isinstance(binding, dict) or str(binding.get("HostPort")) != expected_host_port:
                    errors.append("RUNTIME_PROXY_PORT_REMAP:" + port)
            active = actual_ports.get(port)
            if not isinstance(active, list) or not active:
                errors.append("RUNTIME_PROXY_PORT_NOT_PUBLISHED:" + port)
            elif any(
                not isinstance(binding, dict) or str(binding.get("HostPort")) != expected_host_port
                for binding in active
            ):
                errors.append("RUNTIME_PROXY_ACTIVE_PORT_REMAP:" + port)
        if any(bindings for port, bindings in actual_ports.items() if port not in EXPECTED_PORTS):
            errors.append("RUNTIME_UNEXPECTED_ACTIVE_PROXY_PORT")

    if len(projects) != 1:
        errors.append("RUNTIME_COMPOSE_PROJECT_MISMATCH")
    if len(networks) == 3 and (
        not networks["velocity"] or networks["velocity"] != networks["paper"]
        or networks["paper"] != networks["postgres"]
    ):
        errors.append("RUNTIME_COMPOSE_NETWORK_PARTITION")

    paper_env = _env(containers.get("paper") or {})
    velocity_env = _env(containers.get("velocity") or {})
    if paper_env.get("ONLINE_MODE", "").upper() != "FALSE":
        errors.append("RUNTIME_PAPER_MODE_INCORRECT")
    paper_secret = paper_env.get("PAPER_VELOCITY_SECRET")
    velocity_secret = velocity_env.get("VELOCITY_FORWARDING_SECRET")
    if not paper_secret or not velocity_secret:
        errors.append("RUNTIME_FORWARDING_SECRET_MISSING")
    elif paper_secret != velocity_secret:
        errors.append("RUNTIME_FORWARDING_SECRET_MISMATCH")
    return errors


def _container(service: str) -> dict[str, Any]:
    net = "minecraft-server-dev_default"
    ports = {}
    if service == "velocity":
        ports = {
            key: [{"HostIp": "0.0.0.0", "HostPort": value}]
            for key, value in EXPECTED_PORTS.items()
        }
    env = (
        ["ONLINE_MODE=FALSE", "PAPER_VELOCITY_SECRET=fixture-secret"]
        if service == "paper" else
        ["VELOCITY_FORWARDING_SECRET=fixture-secret"] if service == "velocity" else []
    )
    return {
        "State": {"Running": True},
        "Config": {
            "Env": env,
            "Labels": {"com.docker.compose.service": service,
                       "com.docker.compose.project": "minecraft-server-dev"},
        },
        "HostConfig": {"Privileged": False, "NetworkMode": net, "PortBindings": copy.deepcopy(ports)},
        "NetworkSettings": {
            "Networks": {net: {}},
            "Ports": copy.deepcopy(ports) if service == "velocity"
                     else {"25565/tcp": None} if service == "paper"
                     else {"5432/tcp": None},
        },
    }


def _fixture() -> dict[str, dict[str, Any]]:
    return {name: _container(name) for name in SERVICES}


class RunningBoundaryTests(unittest.TestCase):
    def test_valid(self) -> None:
        self.assertEqual(audit_running(_fixture()), [])

    def _reject(self, edit: Any, expected: str) -> None:
        data = _fixture()
        edit(data)
        self.assertIn(expected, audit_running(data))

    def test_paper_publication(self) -> None:
        self._reject(
            lambda d: d["paper"]["HostConfig"]["PortBindings"].update(
                {"25565/tcp": [{"HostPort": "25565", "HostIp": "0.0.0.0"}]}),
            "RUNTIME_PUBLISHED_BACKEND_PORT:paper",
        )

    def test_postgres_publication(self) -> None:
        self._reject(
            lambda d: d["postgres"]["HostConfig"]["PortBindings"].update(
                {"5432/tcp": [{"HostPort": "5432", "HostIp": "127.0.0.1"}]}),
            "RUNTIME_PUBLISHED_BACKEND_PORT:postgres",
        )

    def test_backend_actual_published_port(self) -> None:
        self._reject(
            lambda d: d["paper"]["NetworkSettings"]["Ports"].update(
                {"25565/tcp": [{"HostPort": "5555", "HostIp": "0.0.0.0"}]}),
            "RUNTIME_ACTIVE_BACKEND_PORT:paper",
        )

    def test_proxy_unexpected_public_port(self) -> None:
        self._reject(
            lambda d: d["velocity"]["HostConfig"]["PortBindings"].update(
                {"25575/tcp": [{"HostPort": "25575", "HostIp": "0.0.0.0"}]}),
            "RUNTIME_PROXY_PORT_SET_MISMATCH",
        )

    def test_proxy_missing_public_port(self) -> None:
        self._reject(
            lambda d: d["velocity"]["HostConfig"]["PortBindings"].pop("19132/udp"),
            "RUNTIME_MISSING_PROXY_PORT:19132/udp",
        )

    def test_proxy_remapped_host_port(self) -> None:
        self._reject(
            lambda d: d["velocity"]["HostConfig"]["PortBindings"]["25565/tcp"][0].update(
                HostPort="25566"),
            "RUNTIME_PROXY_PORT_REMAP:25565/tcp",
        )

    def test_proxy_docker_runtime_port_changed(self) -> None:
        self._reject(
            lambda d: d["velocity"]["NetworkSettings"]["Ports"]["25565/tcp"][0].update(
                HostPort="25566"),
            "RUNTIME_PROXY_ACTIVE_PORT_REMAP:25565/tcp",
        )

    def test_host_networking(self) -> None:
        self._reject(
            lambda d: d["paper"]["HostConfig"].update(NetworkMode="host"),
            "RUNTIME_UNEXPECTED_NETWORK_MODE:paper",
        )

    def test_extra_network(self) -> None:
        self._reject(
            lambda d: d["paper"]["NetworkSettings"]["Networks"].update(other={}),
            "RUNTIME_UNEXPECTED_NETWORK_MODE:paper",
        )

    def test_disjoint_network(self) -> None:
        def mutate(d: dict[str, dict[str, Any]]) -> None:
            d["postgres"]["NetworkSettings"]["Networks"] = {"wrong_net": {}}
            d["postgres"]["HostConfig"]["NetworkMode"] = "wrong_net"
        self._reject(mutate, "RUNTIME_COMPOSE_NETWORK_PARTITION")

    def test_wrong_compose_project(self) -> None:
        self._reject(
            lambda d: d["paper"]["Config"]["Labels"].update(
                {"com.docker.compose.project": "unrelated"}),
            "RUNTIME_COMPOSE_PROJECT_MISMATCH",
        )

    def test_forged_service_label(self) -> None:
        self._reject(
            lambda d: d["paper"]["Config"]["Labels"].update(
                {"com.docker.compose.service": "velocity"}),
            "RUNTIME_SERVICE_LABEL_MISMATCH:paper",
        )

    def test_privileged_container(self) -> None:
        self._reject(
            lambda d: d["paper"]["HostConfig"].update(Privileged=True),
            "RUNTIME_PRIVILEGED_CONTAINER:paper",
        )

    def test_paper_online_mode_wrong(self) -> None:
        self._reject(
            lambda d: d["paper"]["Config"]["Env"].__setitem__(0, "ONLINE_MODE=TRUE"),
            "RUNTIME_PAPER_MODE_INCORRECT",
        )

    def test_secret_mismatch(self) -> None:
        self._reject(
            lambda d: d["paper"]["Config"]["Env"].__setitem__(
                1, "PAPER_VELOCITY_SECRET=wrong-fixture"),
            "RUNTIME_FORWARDING_SECRET_MISMATCH",
        )

    def test_secret_missing(self) -> None:
        self._reject(
            lambda d: d["paper"]["Config"]["Env"].pop(),
            "RUNTIME_FORWARDING_SECRET_MISSING",
        )

    def test_stopped_container(self) -> None:
        self._reject(
            lambda d: d["paper"]["State"].update(Running=False),
            "RUNTIME_NOT_RUNNING:paper",
        )


def inspect_running() -> int:
    if sys.version_info < (3, 11):
        print("RUNNING_PROXY_BOUNDARY_FAIL python3.11-required")
        return 2
    try:
        containers = {}
        for service in SERVICES:
            ps = subprocess.run(
                ["docker", "compose", "-f", "compose.yaml", "ps", "--all", "-q", service],
                cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                check=True, timeout=20, text=True,
            )
            ids = [line.strip() for line in ps.stdout.splitlines() if line.strip()]
            if len(ids) != 1:
                print("RUNNING_PROXY_BOUNDARY_FAIL wrong-container-count:" + service)
                return 1
            result = subprocess.run(
                ["docker", "inspect", ids[0]],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                check=True, timeout=20,
            )
            details = json.loads(result.stdout)
            if not isinstance(details, list) or len(details) != 1:
                print("RUNNING_PROXY_BOUNDARY_FAIL invalid-inspect-response:" + service)
                return 1
            containers[service] = details[0]
    except (OSError, ValueError, subprocess.SubprocessError):
        # Critical: never print Docker inspect stdout or exception/stderr:
        # container metadata contains plaintext environment credentials.
        print("RUNNING_PROXY_BOUNDARY_FAIL cannot-read-runtime")
        return 2
    errors = audit_running(containers)
    if errors:
        for error in sorted(set(errors)):
            print("RUNNING_PROXY_BOUNDARY_FAIL " + error)
        return 1
    print("RUNNING_PROXY_BOUNDARY_PASS dev-compose-containers")
    print("RUNNING_REAL_JAVA_BEDROCK_AUTH_AND_DEPLOYED_FIREWALL_UNVERIFIED issue-47")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Audit running Velocity, Paper and PostgreSQL")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[sys.argv[0]], verbosity=2)
    sys.exit(inspect_running())
