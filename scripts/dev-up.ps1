$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

Write-Host "[1/3] Checking Docker..."
docker info | Out-Null

Write-Host "[2/3] Building MinecraftServerCore with Gradle 9.8.2 / JDK 25..."
docker run --rm `
  -v "${repoRoot}:/workspace" `
  -w /workspace `
  gradle:9.8.2-jdk25 `
  gradle clean build --no-daemon

Write-Host "[3/3] Starting PostgreSQL, Paper, Velocity, Geyser and Floodgate..."
docker compose up -d --wait

docker compose ps
Write-Host ""
Write-Host "Java:   localhost:25565"
Write-Host "Bedrock: localhost:19132"
Write-Host "Run .\scripts\dev-check.ps1 for a health check."
