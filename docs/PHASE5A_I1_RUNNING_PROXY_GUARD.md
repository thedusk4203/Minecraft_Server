# Phase 5A I1 — Running Velocity/Paper Docker Boundary Guard

Status: **implemented as a development-stack runtime probe; NOT authenticated-account staging acceptance**.

Tracking: [#47](https://github.com/thedusk4203/Minecraft_Server/issues/47), parent [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42), commerce readiness [#41](https://github.com/thedusk4203/Minecraft_Server/issues/41).

## Why an additional guard?

The preceding static guard `tools/security/verify_proxy_boundary.py` checks the normalized `compose.yaml` plus `velocity.toml`. A valid Compose declaration alone does not prove that the **running Docker containers** match it.

Per [PaperMC Velocity server security guidance](https://docs.papermc.io/velocity/security/), an offline-mode Paper backend must not be directly exposed to untrusted clients. Modern forwarding is valuable but **does not replace** network isolation/firewalling.

This gate runs after `docker compose up -d --wait` on the already existing CI development stack and inspects the three live containers without adding new services or changing credentials.

## Command and CI position

```bash
python3 tools/security/verify_running_proxy_boundary.py --self-test
python3 tools/security/verify_running_proxy_boundary.py
```

CI executes this immediately after **Start local stack**, before normal database, crossplay, Stone Warden and Stone Vault smoke/E2E verification. Both `docker compose ps --all -q` and `docker inspect` operate on the local ephemeral development stack. It uses only Python 3.11+ standard library.

## Runtime assertions

- All three services `velocity`, `paper`, `postgres` exist, are running, carry the expected Docker Compose service labels and share one consistent Compose project.
- None is privileged or using unexpected host/custom networking; all share the same **single Docker network** in the development stack.
- **Paper** has no HostConfig-published ports and no active published port; mere Docker `EXPOSE` metadata is not treated as a public publication.
- **PostgreSQL** similarly has no host-published ports.
- **Velocity alone** publishes Java 25565/TCP and Bedrock 19132/TCP+UDP, with exact host-port mappings and no additional published targets.
- The actual running container environment still sets Paper `ONLINE_MODE=FALSE`, and the Paper and Velocity forwarding values are both present and equal.
- Every failure emits a fixed error code only; it never logs `docker inspect` JSON, the real forwarding secret, the RCON password or database credentials.

The self-test contains **18 deterministic positive/negative tests**, including published Paper/PostgreSQL ports, unexpected proxy port/remapping, real Docker mappings inconsistent with expected declarations, host/extra/disjoint networks, Compose service label/project mismatch, privileged/stopped containers, wrong Paper mode and missing/mismatched forwarding values.

## Security scope and limitations

This is a **runtime observation of the Docker development Compose stack**, not a deployment attestation.

- Running-container inspection cannot prove cloud security groups, router firewalls, interhost paths, host packet filter policy or restrictions on other containers attached to the same Docker bridge.
- Matching forwarding secret strings in container environment **do not prove** that Paper/Velocity/Floodgate successfully used those strings.
- Real authenticated Java and Xbox/Bedrock sessions (including Floodgate globally linked accounts) are still required in **Issue #47**.
- The current Geyser and Floodgate artifacts run at Velocity only; there is no backend Floodgate-Spigot API attestation yet. No key.pem is installed, copied, read or logged here.
- Offline synthetic Mineflayer gameplay E2Es remain distinct and cannot establish a verified commerce buyer.
- The separate `compose.e2e.yaml` and loopback ViaProxy have their own regression guards from PR #50; this check does not use or change them.
- The tool's static and live observations do not make a currently unverified `player_identity` row eligible for paid entitlements.

## Merge/acceptance gates

1. Exact PR head: all runtime guard assertions and 18 mutation tests plus full CI/Minecraft gameplay tests pass.
2. Post-merge `main`: same CI passes at exact merge SHA.
3. Publish redacted evidence in #47; do **not** close #47, #42 or #41. Secure, authenticated staging verification and owner approval still block any actual commerce implementation.

No production proxy setting, server code, schema migration, payment logic, Geyser/Floodgate installation, game reward or login policy is changed by this PR.
