# Local development

## Prerequisites

Only Docker Desktop (or Docker Engine + Compose) is required for the normal local workflow. Java and Gradle are run in containers.

## Start on Windows

```powershell
.\scripts\dev-up.ps1
```

The script builds `MinecraftServerCore` with Gradle 9.8.2 / JDK 25 and starts:

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

## Security boundary

The credentials and forwarding secret in `compose.yaml` are development-only placeholders. They must never be reused for staging or production. Production will use generated secrets and host/network firewall rules, with backend Paper ports inaccessible from the public internet.

## Why the backend is offline-mode

Velocity performs Mojang authentication. The Paper backend therefore has `online-mode=false` and accepts player identity only through Velocity modern forwarding. Paper's Velocity support is enabled in `config/paper/paper-global.yml`.

## Crossplay

Geyser and Floodgate are installed on Velocity from their official download API on container startup. Geyser runs at the proxy layer; it is intentionally not installed on the Paper backend.
