# Local development

## Prerequisites

Only Docker Desktop (or Docker Engine + Compose) is required for the normal local workflow. Java and Gradle are run in containers.

## Start on Windows

```powershell
.\scripts\dev-up.ps1
```

On first run, the script creates a git-ignored `.env` containing random local-only PostgreSQL, RCON and Velocity-forwarding credentials. It then builds `MinecraftServerCore` with Gradle 9.8.2 / JDK 25 and starts:

- PostgreSQL
- Paper 26.3 backend
- GriefPrevention 16.18.7 on Paper
- AuraSkills 2.4.0 on Paper
- Velocity 4.3.1 proxy
- Geyser-Velocity
- Floodgate-Velocity

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

After land is protected, the current guidance asks the player to gain an AuraSkills level. Use `/skills` to view available skills and perform ordinary survival activities such as mining, farming, foraging or combat.

The first observed AuraSkills level-up records:

- first skill timestamp;
- first skill ID;
- level reached;
- first-currency timestamp;
- one immutable economy-ledger credit of `100 COINS`.

The money is granted by `MinecraftServerCore`, not AuraSkills/Vault. The deterministic ledger idempotency key prevents repeated or concurrent level-up events from paying the onboarding grant twice.

### Seller market escrow — Phase 3C2a

Market use is enabled for normal players through `minecraftserver.market`.

Current commands:

- `/market sell <price>` — escrow the entire stack currently held in the main hand and publish it after durable removal from playerdata;
- `/market list` — display up to 20 active LISTED rows;
- `/market cancel <listing #>` — move the seller's listing through RETURN_PENDING and return the item before terminal cancellation;
- `/market recover` — retry interrupted PREPARED/RETURN_PENDING transitions.

A temporary internal PDC marker appears only while an item is participating in the escrow saga. Marked items are locked against normal player movement/use and are kept through death. Do not remove the marker manually; use `/market recover` if a transition is interrupted.

Buyer purchase is intentionally unavailable in Phase 3C2a. A visible listing proves seller-side escrow only; Phase 3C2b will add atomic payment and retry-safe buyer delivery. `market_used_at` is therefore not recorded yet.

Suggested manual recovery checks:

1. Hold a disposable stack and run `/market sell 25`.
2. Confirm the stack leaves inventory and `/market list` shows the new listing.
3. Run `/market cancel <#>` and confirm the exact stack returns.
4. Repeat with a full inventory: cancellation should remain RETURN_PENDING until one slot is free, then `/market recover` should finish it.
5. Reconnect while a recovery is pending and confirm the same transition resumes without an extra item.

## Stop

```powershell
.\scripts\dev-down.ps1
```

## Linux/macOS

```bash
bash scripts/dev-up.sh
```

The Unix startup script follows the same model and creates `.env` with mode `600` when it does not already exist.

## Security boundary

No reusable runtime credential is committed to the repository. `compose.yaml` requires three environment values (`FORWARDING_SECRET`, `POSTGRES_PASSWORD`, and `RCON_PASSWORD`); the local startup scripts generate them into the git-ignored `.env`. CI supplies disposable values only for its isolated smoke-test runner.

Staging and production must use independently generated secrets from the deployment environment/secret store. The backend Paper port must remain inaccessible from the public internet; only Velocity is an ingress point.

## Why the backend is offline-mode

Velocity performs Mojang authentication. The Paper backend therefore has `online-mode=false` and accepts player identity only through Velocity modern forwarding. Paper's Velocity support is enabled in `config/paper/paper-global.yml`.

## Crossplay

Geyser and Floodgate are installed on Velocity from their official download API on container startup. Geyser runs at the proxy layer; it is intentionally not installed on the Paper backend.

Land protection, skill progression and the market command flow are server-side, so Java and Bedrock clients share the same authoritative rules without a required client mod.
