$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

function New-HexSecret([int] $ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

if (-not (Test-Path ".env")) {
    Write-Host "Generating local-only credentials in .env..."
    $envContent = @(
        "FORWARDING_SECRET=$(New-HexSecret 32)",
        "POSTGRES_PASSWORD=$(New-HexSecret 24)",
        "RCON_PASSWORD=$(New-HexSecret 24)"
    ) -join "`n"
    Set-Content -Path ".env" -Value $envContent -Encoding Ascii
}

Write-Host "[1/4] Checking Docker..."
docker info | Out-Null

Write-Host "[2/4] Building MinecraftServerCore with Gradle 9.8.2 / JDK 25..."
docker run --rm `
  -v "${repoRoot}:/workspace" `
  -w /workspace `
  gradle:9.8.2-jdk25 `
  gradle clean build --no-daemon

Write-Host "[3/4] Preparing pinned Geyser/Floodgate artifacts..."
& (Join-Path $PSScriptRoot "fetch-crossplay-plugins.ps1")

Write-Host "[4/4] Starting PostgreSQL, Paper, Velocity, Geyser and Floodgate..."
docker compose up -d --wait

docker compose ps
Write-Host ""
Write-Host "Java:   localhost:25565"
Write-Host "Bedrock: localhost:19132"
Write-Host "Run .\scripts\dev-check.ps1 for a health check."
