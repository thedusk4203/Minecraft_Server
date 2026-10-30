#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

hex_secret() {
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}

if [[ ! -f .env ]]; then
  echo "Generating local-only credentials in .env..."
  {
    echo "FORWARDING_SECRET=$(hex_secret 32)"
    echo "POSTGRES_PASSWORD=$(hex_secret 24)"
    echo "RCON_PASSWORD=$(hex_secret 24)"
  } > .env
  chmod 600 .env
fi

echo "[1/4] Checking Docker..."
docker info >/dev/null

echo "[2/4] Preparing pinned Geyser/Floodgate artifacts..."
bash "$REPO_ROOT/scripts/fetch-crossplay-plugins.sh"

echo "[3/4] Building MinecraftServerCore with Gradle 9.8.2 / JDK 25..."
docker run --rm \
  -v "$REPO_ROOT:/workspace" \
  -w /workspace \
  gradle:9.8.2-jdk25 \
  gradle clean build --no-daemon

echo "[4/4] Starting PostgreSQL, Paper, Velocity, Geyser and Floodgate..."
docker compose up -d --wait

docker compose ps
printf '\nJava:   localhost:25565\nBedrock: localhost:19132\n'
