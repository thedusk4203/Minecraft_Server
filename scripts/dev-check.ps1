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
Write-Host "== Paper gameplay dependencies =="
$plugins = docker compose exec -T paper rcon-cli "plugins"
$plugins | Write-Host
if ($plugins -notmatch "GriefPrevention") {
    throw "GriefPrevention is not loaded in Paper."
}
if ($plugins -notmatch "AuraSkills") {
    throw "AuraSkills is not loaded in Paper."
}

Write-Host ""
Write-Host "== Market command wiring =="
$market = docker compose exec -T paper rcon-cli "market list"
$market | Write-Host
if ($market -notmatch "requires an online player") {
    throw "Market command is not wired to MinecraftServerCore."
}

Write-Host ""
Write-Host "== Recent proxy/plugin logs =="
docker compose logs --tail=80 velocity | Select-String -Pattern "Velocity|Geyser|Floodgate|ERROR|WARN"
