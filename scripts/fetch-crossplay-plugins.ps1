$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputDir = Join-Path $repoRoot "build/crossplay-plugins"
$baseUrl = "https://download.geysermc.org/v2/projects"

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function Get-PinnedPlugin {
    param(
        [Parameter(Mandatory = $true)][string] $Project,
        [Parameter(Mandatory = $true)][string] $Version,
        [Parameter(Mandatory = $true)][int] $Build,
        [Parameter(Mandatory = $true)][string] $DownloadKey,
        [Parameter(Mandatory = $true)][string] $OutputName
    )

    $metadataUrl = "$baseUrl/$Project/versions/$Version/builds/$Build"
    $downloadUrl = "$metadataUrl/downloads/$DownloadKey"
    $outputFile = Join-Path $outputDir $OutputName
    $tempFile = "$outputFile.tmp-$([Guid]::NewGuid().ToString('N'))"

    Write-Host "Resolving pinned $Project $Version build $Build..."
    $metadata = Invoke-RestMethod -Uri $metadataUrl -MaximumRetryCount 4 -RetryIntervalSec 2

    if ($metadata.project_id -ne $Project) {
        throw "Project mismatch: expected $Project, got $($metadata.project_id)"
    }
    if ($metadata.version -ne $Version) {
        throw "Version mismatch: expected $Version, got $($metadata.version)"
    }
    if ([int]$metadata.build -ne $Build) {
        throw "Build mismatch: expected $Build, got $($metadata.build)"
    }

    $download = $metadata.downloads.$DownloadKey
    if ($null -eq $download) {
        throw "Download key missing: $DownloadKey"
    }
    $expectedSha = ([string]$download.sha256).ToLowerInvariant()
    if ($expectedSha -notmatch '^[a-f0-9]{64}$') {
        throw "Upstream metadata returned an invalid SHA-256"
    }
    $upstreamName = [string]$download.name
    if (-not $upstreamName.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Upstream metadata returned an invalid artifact name"
    }

    if (Test-Path $outputFile) {
        $existingSha = (Get-FileHash -Path $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existingSha -eq $expectedSha) {
            Write-Host "$OutputName already matches pinned $Project $Version build $Build ($expectedSha)."
            return
        }
    }

    try {
        Write-Host "Downloading $upstreamName -> $OutputName..."
        Invoke-WebRequest -Uri $downloadUrl -OutFile $tempFile -MaximumRetryCount 4 -RetryIntervalSec 2
        $actualSha = (Get-FileHash -Path $tempFile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualSha -ne $expectedSha) {
            throw "SHA-256 mismatch for $Project $Version build $Build; expected $expectedSha, got $actualSha"
        }
        Move-Item -Force -Path $tempFile -Destination $outputFile
        Write-Host "Verified $OutputName sha256=$expectedSha"
    }
    finally {
        if (Test-Path $tempFile) {
            Remove-Item -Force $tempFile
        }
    }
}

Get-PinnedPlugin -Project "geyser" -Version "2.12.1" -Build 1277 -DownloadKey "velocity" -OutputName "Geyser-Velocity.jar"
Get-PinnedPlugin -Project "floodgate" -Version "2.2.5" -Build 141 -DownloadKey "velocity" -OutputName "floodgate-velocity.jar"

Write-Host "Pinned crossplay plugins are ready in $outputDir"
