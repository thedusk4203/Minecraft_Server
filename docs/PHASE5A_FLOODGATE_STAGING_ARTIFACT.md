# Phase 5A I1 — Pinned Floodgate-Spigot Staging Artifact (not installed)

Status: **BUILD/PROVENANCE REVIEW INPUT ONLY; BACKEND API RUNTIME AND AUTHENTICATED CLIENTS UNVERIFIED**

Parent [Issue #47](https://github.com/thedusk4203/Minecraft_Server/issues/47); identity blocker [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42). This increment does **not** authorize key distribution, backend plugin installation, any production config change or paid entitlement ownership.

## Verified source boundary

The existing `scripts/fetch-crossplay-plugins.sh` already clones and builds the **entire** pinned Floodgate repository and recursively pinned submodules, but previously copied only the Velocity jar. This change additionally stages the **Spigot/Paper backend jar from the same exact source build**, without installing it anywhere.

Pinned upstream reference: `GeyserMC/Floodgate` commit `81b65ccb766bf8024858ebf3eefa6974056aecc5`; build number `141`; source version `2.2.5-SNAPSHOT`; descriptor `2.2.5-SNAPSHOT (b141-81b65cc)`. Source references:

- [Floodgate v2.2.5 Spigot plugin descriptor](https://github.com/GeyserMC/Floodgate/blob/81b65ccb766bf8024858ebf3eefa6974056aecc5/spigot/src/main/resources/plugin.yml)
- [Floodgate Spigot module build rules](https://github.com/GeyserMC/Floodgate/blob/81b65ccb766bf8024858ebf3eefa6974056aecc5/spigot/build.gradle.kts)
- [Floodgate API interface](https://github.com/GeyserMC/Floodgate/blob/81b65ccb766bf8024858ebf3eefa6974056aecc5/api/src/main/java/org/geysermc/floodgate/api/FloodgateApi.java)

The pinned API exposes `isFloodgatePlayer(UUID)` for a currently online Bedrock player, `getPlayer(UUID)` and `isFloodgateId(UUID)`. **The latter is not proof of a linked Bedrock player**; use trusted active-session evidence instead, as in `docs/PHASE5A_IDENTITY_TRUST_DESIGN.md`.

## Staging-only artifact flow

```text
Pinned Floodgate source + pinned submodules
                |
        Gradle full build in Docker
                |
     +----------+--------------------+
     |                               |
 floodgate-velocity.jar       floodgate-spigot-staging.jar
  (existing Velocity)           (NOT INSTALLED)
     |                               |
 velocity descriptor check       plugin.yml/main class check
     |                               |
 velocity provenance SHA-256     staging-only provenance SHA-256
```

`build/crossplay-plugins/floodgate-spigot-staging.jar` and its `.provenance` file are generated **locally by the build script**. They are not source-controlled, uploaded to permanent release storage, or mounted into either `compose.yaml` or `compose.e2e.yaml`.

Both the build script and CI verify the staged jar's descriptor/version and/or class, source commit, build number and recorded SHA-256. The cache can only be used when **both** the Velocity and staging backend artifacts and their provenance files pass verification.

### Execution

```bash
bash scripts/fetch-crossplay-plugins.sh
test -s build/crossplay-plugins/floodgate-spigot-staging.jar
test -s build/crossplay-plugins/floodgate-spigot-staging.provenance
```

The repository's normal `scripts/fetch-crossplay-plugins.ps1` workflow is unchanged: this staging artifact is initially prepared by the **Bash/CI** path only. Do not assume this alone certifies cross-platform packaging. CI verifies the stage artifact **before** bringing up the ordinary development stack and continues to run all existing crossplay/Paper and Stone Warden/Stone Vault acceptance tests.

## Threat model and limitations

This is a **source-pinned build with locally recorded provenance**, not a third-party signed release, deterministic bit-for-bit reproducibility certificate or security audit of the complete dependency tree.

- A jar existing with the correct `plugin.yml` and main class does **not** prove that the Floodgate backend API is compatible with Paper 26.3, that the proxy safely delivers authentication data, or that a real linked Bedrock player's XUID can be verified at runtime.
- Backend Floodgate requires separate proxy/backend configuration, matching protected `key.pem`, correct `send-floodgate-data` and tested trust boundary; none is set here. See [GeyserMC Floodgate API](https://geysermc.org/wiki/floodgate/api/) and [proxy setup](https://geysermc.org/wiki/floodgate/setup/proxy-servers/).
- No artifacts from this PR become ordinary Paper plugins. A later reviewed isolated **staging deployment** must decide exactly how to provision/rotate secrets without exposing them in GitHub, logs, Docker build contexts or backups.
- No player identifier, `IdentityPlatform.UNKNOWN`, canonical `player_id`, market/COINS ledger or Phase 4D reward path changes here.
- Real authenticated Java and Bedrock client tests, linked/unlinked subjects, denial of direct Paper access, key mismatch and session replay protections remain **OPEN** under #47.

## Acceptance

1. Exact-head full CI proves successful source-build/descriptor/provenance checks and that the staged jar is absent from Compose mounts.
2. Merged `main` full CI also succeeds before citing the staging artifact as a retained foundation.
3. No Issue #47/#42 closure until runtime backend API and authenticated-user staging verification with a redacted evidence receipt and technical/security signoff. Phase 5B and real payment checkout remain blocked.
