#!/usr/bin/env python3
"""Fail-closed static audit of the DEV Velocity/Paper Compose trust boundary.

This does not authenticate a Java/Bedrock player, test an actual firewall,
attest a deployed host or enable commerce. Never print resolved Compose data:
it contains the interpolated forwarding/database/RCON secrets.
"""
from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
import tomllib
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_PUBLIC = {(25565, "tcp"), (19132, "tcp"), (19132, "udp")}
EXPECTED_PLUGIN_TARGETS = {
    "/server/plugins/Geyser-Velocity.jar",
    "/server/plugins/floodgate-velocity.jar",
}


def _service(services: dict[str, Any], name: str, errors: list[str]) -> dict[str, Any]:
    value = services.get(name)
    if not isinstance(value, dict):
        errors.append("MISSING_SERVICE:" + name)
        return {}
    return value


def _environment(service: dict[str, Any]) -> dict[str, Any]:
    env = service.get("environment", {})
    if isinstance(env, dict):
        return env
    if isinstance(env, list):
        return dict(str(item).split("=", 1) for item in env if "=" in str(item))
    return {}


def _ports(service: dict[str, Any], errors: list[str], label: str) -> set[tuple[int, str]]:
    found: set[tuple[int, str]] = set()
    ports = service.get("ports") or []
    if not isinstance(ports, list):
        errors.append("INVALID_PORT_MODEL:" + label)
        return found
    for port in ports:
        if not isinstance(port, dict):
            errors.append("UNNORMALIZED_PORT_MODEL:" + label)
            continue
        try:
            target = int(port["target"])
            protocol = str(port.get("protocol", "tcp")).lower()
            # A target without a published port is not externally reachable.
            published = str(port.get("published", ""))
            if not published or not published.isdecimal():
                errors.append("UNPUBLISHED_PORT_MODEL:" + label)
                continue
            if not 1 <= int(published) <= 65535:
                errors.append("INVALID_PUBLISHED_PORT:" + label)
                continue
        except (KeyError, TypeError, ValueError):
            errors.append("INVALID_PORT_ENTRY:" + label)
            continue
        found.add((target, protocol))
    return found


def audit(model: dict[str, Any], config: dict[str, Any]) -> list[str]:
    """Return error CODES only: no paths containing secrets, no secret values."""
    errors: list[str] = []
    services = model.get("services", {})
    if not isinstance(services, dict):
        return ["INVALID_SERVICES"]
    velocity = _service(services, "velocity", errors)
    paper = _service(services, "paper", errors)
    postgres = _service(services, "postgres", errors)

    for name, service in (("velocity", velocity), ("paper", paper), ("postgres", postgres)):
        if service.get("privileged") is True:
            errors.append("PRIVILEGED_SERVICE:" + name)
        if service.get("network_mode") is not None:
            errors.append("CUSTOM_NETWORK_MODE:" + name)

    if paper.get("ports"):
        errors.append("PUBLIC_PAPER_BACKEND_PORT")
    if postgres.get("ports"):
        errors.append("PUBLIC_POSTGRES_PORT")
    if _ports(velocity, errors, "velocity") != EXPECTED_PUBLIC:
        errors.append("INCORRECT_PROXY_PUBLIC_PORTS")

    paper_env = _environment(paper)
    velocity_env = _environment(velocity)
    if str(paper_env.get("ONLINE_MODE", "")).upper() != "FALSE":
        errors.append("PAPER_BACKEND_NOT_OFFLINE")
    paper_secret = paper_env.get("PAPER_VELOCITY_SECRET")
    proxy_secret = velocity_env.get("VELOCITY_FORWARDING_SECRET")
    if not isinstance(paper_secret, str) or not paper_secret:
        errors.append("PAPER_FORWARDING_SECRET_MISSING")
    if not isinstance(proxy_secret, str) or not proxy_secret:
        errors.append("VELOCITY_FORWARDING_SECRET_MISSING")
    if paper_secret and proxy_secret and paper_secret != proxy_secret:
        errors.append("FORWARDING_SECRET_MISMATCH")

    if config.get("online-mode") is not True:
        errors.append("VELOCITY_ONLINE_MODE_DISABLED")
    if config.get("force-key-authentication") is not True:
        errors.append("VELOCITY_KEY_AUTH_DISABLED")
    if config.get("player-info-forwarding-mode") != "modern":
        errors.append("VELOCITY_NOT_MODERN_FORWARDING")
    if config.get("forwarding-secret-file") != "forwarding.secret":
        errors.append("UNEXPECTED_VELOCITY_FORWARDING_SECRET_SOURCE")
    if (config.get("servers") or {}).get("survival") != "paper:25565":
        errors.append("UNEXPECTED_VELOCITY_BACKEND_TARGET")

    mounts = velocity.get("volumes") or []
    targets = {
        item.get("target")
        for item in mounts
        if isinstance(item, dict) and isinstance(item.get("target"), str)
    }
    if not EXPECTED_PLUGIN_TARGETS.issubset(targets):
        errors.append("MISSING_PROXY_CROSSPLAY_ARTIFACT_MOUNT")
    return errors


def _dummy() -> tuple[dict[str, Any], dict[str, Any]]:
    """Synthetic unit fixture; no actual secret or credential is embedded."""
    model = {
        "services": {
            "velocity": {
                "environment": {"VELOCITY_FORWARDING_SECRET": "synthetic-fixture"},
                "ports": [
                    {"target": 25565, "published": "25565", "protocol": "tcp"},
                    {"target": 19132, "published": "19132", "protocol": "tcp"},
                    {"target": 19132, "published": "19132", "protocol": "udp"},
                ],
                "volumes": [{"target": target} for target in sorted(EXPECTED_PLUGIN_TARGETS)],
            },
            "paper": {
                "environment": {
                    "ONLINE_MODE": "FALSE",
                    "PAPER_VELOCITY_SECRET": "synthetic-fixture",
                },
            },
            "postgres": {},
        }
    }
    config = {
        "online-mode": True,
        "force-key-authentication": True,
        "player-info-forwarding-mode": "modern",
        "forwarding-secret-file": "forwarding.secret",
        "servers": {"survival": "paper:25565"},
    }
    return model, config


class SecurityBoundaryRegression(unittest.TestCase):
    def test_expected_trust_boundary(self) -> None:
        self.assertEqual(audit(*_dummy()), [])

    def _expect_rejected(self, mutation: Any, code: str) -> None:
        model, config = copy.deepcopy(_dummy())
        mutation(model, config)
        self.assertIn(code, audit(model, config))

    def test_public_paper_backend_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["paper"].update(
                ports=[{"target": 25565, "published": "25567", "protocol": "tcp"}]
            ),
            "PUBLIC_PAPER_BACKEND_PORT",
        )

    def test_public_database_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["postgres"].update(
                ports=[{"target": 5432, "published": "5432", "protocol": "tcp"}]
            ),
            "PUBLIC_POSTGRES_PORT",
        )

    def test_host_networking_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["paper"].update(network_mode="host"),
            "CUSTOM_NETWORK_MODE:paper",
        )

    def test_unauthenticated_velocity_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: c.update({"online-mode": False}),
            "VELOCITY_ONLINE_MODE_DISABLED",
        )

    def test_legacy_forwarding_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: c.update({"player-info-forwarding-mode": "legacy"}),
            "VELOCITY_NOT_MODERN_FORWARDING",
        )

    def test_wrong_forwarding_secret_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["paper"]["environment"].update(
                PAPER_VELOCITY_SECRET="another-synthetic-fixture"
            ),
            "FORWARDING_SECRET_MISMATCH",
        )

    def test_missing_forwarding_secret_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["paper"]["environment"].pop(
                "PAPER_VELOCITY_SECRET"
            ),
            "PAPER_FORWARDING_SECRET_MISSING",
        )

    def test_missing_bedrock_udp_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["velocity"]["ports"].pop(),
            "INCORRECT_PROXY_PUBLIC_PORTS",
        )

    def test_unreviewed_proxy_public_port_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["velocity"]["ports"].append(
                {"target": 25575, "published": "25575", "protocol": "tcp"}
            ),
            "INCORRECT_PROXY_PUBLIC_PORTS",
        )

    def test_backend_target_outside_proxy_compose_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: c["servers"].update(survival="untrusted-host:25565"),
            "UNEXPECTED_VELOCITY_BACKEND_TARGET",
        )

    def test_missing_crossplay_mount_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["velocity"]["volumes"].clear(),
            "MISSING_PROXY_CROSSPLAY_ARTIFACT_MOUNT",
        )

    def test_backend_not_offline_fails_closed(self) -> None:
        self._expect_rejected(
            lambda m, c: m["services"]["paper"]["environment"].update(
                ONLINE_MODE="TRUE"
            ),
            "PAPER_BACKEND_NOT_OFFLINE",
        )


def inspect_local() -> int:
    if sys.version_info < (3, 11):
        print("STAGING_STATIC_PROXY_BOUNDARY_FAIL python3.11-or-newer-required")
        return 2
    try:
        command = ["docker", "compose", "-f", "compose.yaml", "config", "--format", "json"]
        # Docker Compose resolves forwarding/DB/RCON secrets. Never print stdout
        # or stderr here, even if JSON parsing or command execution fails.
        result = subprocess.run(
            command, cwd=ROOT, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, check=True, timeout=45,
        )
        model = json.loads(result.stdout)
        config = tomllib.loads((ROOT / "config/velocity/velocity.toml").read_text("utf-8"))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("STAGING_STATIC_PROXY_BOUNDARY_FAIL cannot-read-local-config")
        return 2
    issues = audit(model, config)
    if issues:
        for issue in sorted(set(issues)):
            print("STAGING_STATIC_PROXY_BOUNDARY_FAIL " + issue)
        return 1
    print("STAGING_STATIC_PROXY_BOUNDARY_PASS dev-compose-only")
    print("STAGING_REAL_AUTHENTICATION_AND_FIREWALL_UNVERIFIED see-issue-47")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Audit static Velocity/Paper trust boundary")
    parser.add_argument("--self-test", action="store_true", help="Run negative security mutation tests")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[sys.argv[0]], exit=True, verbosity=2)
    sys.exit(inspect_local())
