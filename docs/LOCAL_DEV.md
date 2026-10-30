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

The check validates PostgreSQL readiness, invokes `/mcserver health` through Paper RCON, and prints recent Velocity/Geyser/Floodgate logs.

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
