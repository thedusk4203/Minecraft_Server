# Local development

## Prerequisites

Only Docker Desktop (or Docker Engine + Compose) is required for the normal local workflow. Java and Gradle are run in containers. The startup scripts prepare pinned Geyser/Floodgate artifacts before Compose starts.

## Start on Windows

```powershell
.\scripts\dev-up.ps1
```

On first run, the script creates a git-ignored `.env` containing random local-only PostgreSQL, RCON and Velocity-forwarding credentials. It verifies/builds the pinned crossplay artifacts, builds `MinecraftServerCore` with Gradle 9.8.2 / JDK 25 and starts:

- PostgreSQL
- Paper 26.3 backend
- GriefPrevention 16.18.7 on Paper
- AuraSkills 2.4.0 on Paper
- Velocity 4.3.1 proxy
- Geyser-Velocity 2.12.1-b1277
- Floodgate-Velocity 2.2.5 build 141

Endpoints:

- Java Edition: `localhost:25565`
- Bedrock Edition: `localhost:19132`

## Verify

```powershell
.\scripts\dev-check.ps1
```

The check validates PostgreSQL readiness, invokes `/mcserver health` through Paper RCON, confirms GriefPrevention and AuraSkills are loaded, verifies the market command is registered, and prints recent Velocity/Geyser/Floodgate logs.

Administrative commands require `minecraftserver.admin` (operator by default):

- `/mcserver health` returns immediate process/pool liveness without JDBC work on the Minecraft main thread.
- `/mcserver diagnostics` runs a PostgreSQL-backed durable-state snapshot asynchronously and reports player/economy/audit counts.

Both command paths carry correlation IDs and are written to the audit log. `health` is the RCON/container smoke-test command; use `diagnostics` when investigating durable state.

## Phase 3 onboarding check

### Land claim

A player who has not yet created a protected claim receives the current onboarding objective on join. Use GriefPrevention's normal golden-shovel claim flow; a successful non-cancelled claim records the durable `land_claimed_at` milestone.

Reconnect before completing the claim objective intentionally repeats the reminder. PostgreSQL remains authoritative for onboarding progress; GriefPrevention remains authoritative for claim geometry and ownership.

### First skill and currency

After land is protected, the guidance asks the player to gain an AuraSkills level. Use `/skills` and perform ordinary survival activities such as mining, farming, foraging or combat.

The first observed level-up records the first skill and grants one `100 COINS` teaching credit through `MinecraftServerCore`; AuraSkills/Vault does not mutate the authoritative economy.

### End-to-end market loop

Market use is enabled for normal players through `minecraftserver.market`.

Commands:

- `/market sell <price>` — escrow the entire main-hand stack and publish it after durable playerdata removal;
- `/market list` — show up to 20 active listings;
- `/market buy <listing #>` — atomically pay one seller and start retry-safe buyer delivery;
- `/market cancel <listing #>` — cancel your unsold listing and recover the escrow item;
- `/market recover` — retry interrupted seller escrow and buyer delivery state.

A temporary internal PDC escrow token is used while an item participates in a seller return or buyer delivery saga. Marked items are locked against normal movement/use and are kept through death. Do not remove the marker manually.

Suggested two-player verification:

1. Player A completes claim + first skill/currency onboarding, holds a disposable stack and runs `/market sell 25`.
2. Confirm the stack leaves A's inventory and `/market list` exposes one LISTED row.
3. Player B, with at least 25 COINS, runs `/market buy <#>`.
4. Confirm B receives exactly one item, B loses exactly 25 COINS and A gains exactly 25 COINS.
5. Re-run `/market buy <#>` as B; it must not charge again or create another item.
6. Attempt the same listing as a third player; it must no longer be buyable.
7. Reconnect A and B. The sold listing must not reappear and no duplicate item should be produced.
8. Verify both players now have `market_used_at`; payment alone is insufficient—the milestone is written only after buyer delivery reaches COMPLETED.

Suggested recovery checks:

- Seller cancellation with full inventory remains RETURN_PENDING until a slot is free, then `/market recover` finishes it.
- Buyer purchase with full inventory remains DELIVERY_PENDING after payment; free one slot and run `/market recover`.
- Disconnect a buyer after payment but before delivery finishes; reconnect must resume the same purchase without charging twice.
- If a marked delivery item survives but the DB acknowledgement was interrupted, `/market recover` must acknowledge that same item rather than mint another.

## Stop

```powershell
.\scripts\dev-down.ps1
```

## Linux/macOS

```bash
bash scripts/dev-up.sh
```

The Unix startup script follows the same model and creates `.env` with mode `600` when it does not already exist.

## Manual crossplay artifact preparation

The normal startup scripts do this automatically. If you invoke `docker compose` directly, prepare the two pinned proxy plugins first:

```powershell
.\scripts\fetch-crossplay-plugins.ps1
```

or:

```bash
bash scripts/fetch-crossplay-plugins.sh
```

The Geyser path queries the official Modrinth API for the exact `2.12.1-b1277` Velocity/26.3 release, requires the primary file to be `Geyser-Velocity.jar`, requires its published SHA-512 to equal the repository pin, then verifies the downloaded Modrinth CDN artifact against that SHA-512.

Floodgate does not rely on the downloads host. The fetcher downloads the official GeyserMC/Floodgate source archive for exact commit `81b65ccb766bf8024858ebf3eefa6974056aecc5` (`Update to 26.3`), verifies the source declares version `2.2.5`, and builds the Velocity artifact in pinned `gradle:7.6-jdk17` with `BUILD_NUMBER=141`. The resulting JAR is cached with a local provenance record containing source commit, version, build number and its SHA-256.

Prepared artifacts live under the git-ignored `build/crossplay-plugins/` directory and are bind-mounted into Velocity. Compose never asks the proxy container to resolve mutable `latest` plugin URLs at startup.

## Security boundary

No reusable runtime credential is committed to the repository. `compose.yaml` requires three environment values (`FORWARDING_SECRET`, `POSTGRES_PASSWORD`, and `RCON_PASSWORD`); the local startup scripts generate them into the git-ignored `.env`. CI supplies disposable values only for its isolated smoke-test runner.

Crossplay artifact acquisition never disables TLS verification. Geyser is hash-pinned to official Modrinth metadata; Floodgate is built from an immutable official GitHub commit. Updating either artifact requires an explicit repository pin change and the full regression gate.

Staging and production must use independently generated secrets from the deployment environment/secret store. The backend Paper port must remain inaccessible from the public internet; only Velocity is an ingress point.

## Why the backend is offline-mode

Velocity performs Mojang authentication. The Paper backend therefore has `online-mode=false` and accepts player identity only through Velocity modern forwarding. Paper's Velocity support is enabled in `config/paper/paper-global.yml`.

## Crossplay

Geyser and Floodgate run on Velocity and are intentionally not installed on the Paper backend. Their artifacts are pinned and prepared before proxy startup rather than resolved as mutable `latest` downloads inside the proxy container.

Land protection, skill progression and the market command flow are server-side, so Java and Bedrock clients share the same authoritative rules without a required client mod.
