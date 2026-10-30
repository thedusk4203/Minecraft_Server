$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

Write-Host "== Containers =="
docker compose ps

Write-Host ""
Write-Host "== PostgreSQL =="
docker compose exec -T postgres pg_isready -U minecraft -d minecraft

Write-Host ""
Write-Host "== Paper core plugin =="
docker compose exec -T paper rcon-cli "mcserver health"

Write-Host ""
Write-Host "== Paper land protection =="
$plugins = docker compose exec -T paper rcon-cli "plugins"
$plugins | Write-Host
if ($plugins -notmatch "GriefPrevention") {
    throw "GriefPrevention is not loaded in Paper."
}

Write-Host ""
Write-Host "== Recent proxy/plugin logs =="
docker compose logs --tail=80 velocity | Select-String -Pattern "Velocity|Geyser|Floodgate|ERROR|WARN"
