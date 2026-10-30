#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "[1/3] Checking Docker..."
docker info >/dev/null

echo "[2/3] Building MinecraftServerCore with Gradle 9.8.2 / JDK 25..."
docker run --rm \
  -v "$REPO_ROOT:/workspace" \
  -w /workspace \
  gradle:9.8.2-jdk25 \
  gradle clean build --no-daemon

echo "[3/3] Starting PostgreSQL, Paper, Velocity, Geyser and Floodgate..."
docker compose up -d --wait

docker compose ps
printf '\nJava:   localhost:25565\nBedrock: localhost:19132\n'
