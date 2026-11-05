# Phase 5A I1 — Static Proxy Boundary Guard

Status: **IMPLEMENTED FOR DEV-COMPOSE CONFIGURATION ONLY / NOT A STAGING IDENTITY ATTESTATION**

Issue: [#47](https://github.com/thedusk4203/Minecraft_Server/issues/47). Parent identity blocker: [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42). Readiness gate: [#41](https://github.com/thedusk4203/Minecraft_Server/issues/41).

## Motivation

Paper is deliberately `ONLINE_MODE=FALSE` behind a Velocity proxy that authenticates Java clients. Accidental public exposure of Paper can allow client impersonation; accidental direct PostgreSQL exposure introduces a separate serious risk. Per [PaperMC's backend security guidance](https://docs.papermc.io/velocity/security/), Velocity modern forwarding provides a second layer of protection **but does not replace a firewall**.

This PR introduces an inexpensive, deterministic security regression gate for the committed **development Compose model**. It cannot verify the host firewall or prove a real player's Java/Bedrock account.

## What is tested

`tools/security/verify_proxy_boundary.py` reads:

- the normalized JSON from `docker compose -f compose.yaml config --format json` (with existing environment-supplied secrets interpolated);
- `config/velocity/velocity.toml`, using the Python 3.11+ standard-library `tomllib`.

The analyzer rejects:

1. publicly published Paper backend ports;
2. publicly published PostgreSQL ports;
3. `host` or any unexpected `network_mode` on these services, and privileged containers;
4. missing/unexpected, remapped or duplicate proxy public port mapping (Java 25565/TCP, Bedrock 19132/TCP and UDP);
5. backend Paper not set to offline mode (which must be coupled to a trusted proxy);
6. missing or inconsistent Compose-provisioned Paper and Velocity forwarding secrets;
7. Velocity `online-mode` disabled, `force-key-authentication` disabled, or non-`modern` forwarding;
8. unexpected forwarding secret filename or backend routing target;
9. missing Geyser-Velocity or Floodgate-Velocity plugin artifact mounts.

The regression self-test deliberately mutates safe synthetic models to simulate direct backend exposure, PostgreSQL exposure, a bad proxy configuration, missing Bedrock UDP, mismatched secrets and unexpected plugin wiring. The checker reports **only issue codes**, never rendered Compose JSON or secret strings.

Run locally:

```bash
# A local dev environment must have its normal Compose environment variables set.
python3 tools/security/verify_proxy_boundary.py --self-test
python3 tools/security/verify_proxy_boundary.py
```

CI executes both commands immediately after its existing `docker compose config --quiet`, **before** Java build and the longer full-stack E2Es. Existing CI and all positive/failure-path Minecraft tests remain unchanged.

## What this does NOT prove

**Passing the static gate is NOT identity verification and does not authorize commerce.** In particular, it cannot establish:

- the actual VM/router/cloud firewall, Docker host network policies or production deployment differences;
- authenticated, real Java client access or Xbox/XUID ownership on real Bedrock sessions;
- Geyser's runtime `auth-type` or Floodgate's runtime `send-floodgate-data` setting;
- the backend Floodgate-Spigot API, key distribution, matching `key.pem`, signature trust or shared-key rotation;
- the validity of a globally linked Bedrock identity, which may present an effective Java UUID;
- negative real-world proxy bypass probes or actual multi-host isolation;
- refund, purchased entitlement, order, or paid cosmetic behavior.

The isolated `compose.e2e.yaml` intentionally publishes a Paper port for **offline Mineflayer protocol tests** and is out of scope of this **development** topology guard. That separate E2E fixture is not a production network configuration.

For verified Bedrock identity on a Paper backend, the official [Floodgate proxy setup](https://geysermc.org/wiki/floodgate/setup/proxy-servers/) describes the backend plugin, `send-floodgate-data` forwarding and shared authentication key requirements. **Do not commit, print or distribute `key.pem`.** The real authentication and firewall stages remain explicitly OPEN in #47.

## Safe next steps

1. Require the static guard to pass on the exact PR head and merged `main` before citing it as a stable regression gate.
2. Define an opt-in **isolated staging** infrastructure with secure key provisioning and nonpublic Paper backend, plus a documented cleanup and access policy.
3. Test real authenticated Java and Bedrock clients, including globally linked Bedrock identities, and verify that a disabled/mismatched Floodgate backend fails paid eligibility closed without blocking free game access.
4. Publish a redacted staging receipt and have the technical/security owner review it. Keep #42/#47 OPEN until that happens.
5. Only after a separate readiness approval may future commerce identity implementation and Phase 5B mocked entitlements proceed.

No production gameplay, auth wiring, proxy configuration, secrets, payment code or database migration changes in this PR.
