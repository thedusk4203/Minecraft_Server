$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputDir = Join-Path $repoRoot "build/crossplay-plugins"

$geyserProjectId = "wKkoqHrH"
$geyserVersion = "2.12.1-b1277"
$geyserExpectedSha512 = "02559b2aeaacbb7a0916c1e9b830fffd4638f22d79852c854fc258fb37b8ce2b491369fa293944c3678e3021dcaa3d3fc2870532c0fbe7a7854ce483dbf31b2d"
$geyserMetadataUrl = "https://api.modrinth.com/v2/project/$geyserProjectId/version?loaders=%5B%22velocity%22%5D&game_versions=%5B%2226.3%22%5D"

$floodgateVersion = "2.2.5"
$floodgateSourceVersion = "2.2.5-SNAPSHOT"
$floodgateBuild = 141
$floodgateCommit = "81b65ccb766bf8024858ebf3eefa6974056aecc5"
$floodgateSourceUrl = "https://github.com/GeyserMC/Floodgate/archive/$floodgateCommit.zip"
$floodgateGradleImage = "gradle:7.6-jdk17"

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function Invoke-WithRetry {
    param(
        [Parameter(Mandatory = $true)][scriptblock] $Operation,
        [Parameter(Mandatory = $true)][string] $Description
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            return & $Operation
        }
        catch {
            $lastError = $_
            if ($attempt -eq 5) {
                break
            }
            Write-Warning "$Description failed on attempt $attempt/5: $($_.Exception.Message)"
            Start-Sleep -Seconds 2
        }
    }
    throw "$Description failed after 5 attempts: $($lastError.Exception.Message)"
}

function Get-GeyserVelocity {
    $outputFile = Join-Path $outputDir "Geyser-Velocity.jar"
    $tempFile = "$outputFile.tmp-$([Guid]::NewGuid().ToString('N'))"
    $headers = @{ "User-Agent" = "thedusk4203/Minecraft_Server crossplay bootstrap" }

    Write-Host "Resolving pinned Geyser $geyserVersion for Velocity/26.3 from Modrinth..."
    $versions = Invoke-WithRetry -Description "Fetch Geyser metadata from Modrinth" -Operation {
        Invoke-RestMethod -UseBasicParsing -Headers $headers -Uri $geyserMetadataUrl
    }
    $matches = @($versions | Where-Object { $_.version_number -eq $geyserVersion })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one Modrinth version $geyserVersion, found $($matches.Count)"
    }
    $primaryFiles = @($matches[0].files | Where-Object { $_.primary -eq $true })
    if ($primaryFiles.Count -ne 1) {
        throw "Expected exactly one primary Geyser file, found $($primaryFiles.Count)"
    }
    $file = $primaryFiles[0]
    if ([string]$file.filename -ne "Geyser-Velocity.jar") {
        throw "Unexpected Geyser filename: $($file.filename)"
    }
    $metadataSha512 = ([string]$file.hashes.sha512).ToLowerInvariant()
    if ($metadataSha512 -ne $geyserExpectedSha512) {
        throw "Geyser SHA-512 metadata mismatch; expected $geyserExpectedSha512, got $metadataSha512"
    }
    $downloadUrl = [string]$file.url
    if (-not $downloadUrl.StartsWith("https://cdn.modrinth.com/", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected Geyser download URL: $downloadUrl"
    }

    if (Test-Path $outputFile) {
        $existingSha = (Get-FileHash -Path $outputFile -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($existingSha -eq $geyserExpectedSha512) {
            Write-Host "Geyser-Velocity.jar already matches pinned SHA-512."
            return
        }
    }

    try {
        Write-Host "Downloading verified Geyser artifact from Modrinth CDN..."
        Invoke-WithRetry -Description "Download Geyser artifact" -Operation {
            Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $downloadUrl -OutFile $tempFile | Out-Null
        } | Out-Null
        $actualSha = (Get-FileHash -Path $tempFile -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actualSha -ne $geyserExpectedSha512) {
            throw "Geyser SHA-512 mismatch; expected $geyserExpectedSha512, got $actualSha"
        }
        Move-Item -Force -Path $tempFile -Destination $outputFile
        Write-Host "Verified Geyser $geyserVersion sha512=$geyserExpectedSha512"
    }
    finally {
        if (Test-Path $tempFile) {
            Remove-Item -Force $tempFile
        }
    }
}

function Build-FloodgateVelocity {
    $outputFile = Join-Path $outputDir "floodgate-velocity.jar"
    $provenanceFile = Join-Path $outputDir "floodgate-velocity.provenance"

    if ((Test-Path $outputFile) -and (Test-Path $provenanceFile)) {
        $cachedSha = (Get-FileHash -Path $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $provenance = Get-Content -Path $provenanceFile
        if (($provenance -contains "commit=$floodgateCommit") -and
            ($provenance -contains "build=$floodgateBuild") -and
            ($provenance -contains "sha256=$cachedSha")) {
            Write-Host "floodgate-velocity.jar already matches pinned source provenance."
            return
        }
    }

    $sourceZip = Join-Path $outputDir ".floodgate-source-$([Guid]::NewGuid().ToString('N')).zip"
    $extractRoot = Join-Path $outputDir ".floodgate-build-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null

    try {
        Write-Host "Downloading Floodgate $floodgateVersion build $floodgateBuild source at $floodgateCommit from GitHub..."
        Invoke-WithRetry -Description "Download Floodgate source" -Operation {
            Invoke-WebRequest -UseBasicParsing -Uri $floodgateSourceUrl -OutFile $sourceZip | Out-Null
        } | Out-Null
        Expand-Archive -Path $sourceZip -DestinationPath $extractRoot -Force

        $sourceDir = Get-ChildItem -Path $extractRoot -Directory | Select-Object -First 1
        if ($null -eq $sourceDir) {
            throw "Floodgate source archive did not contain a source directory"
        }
        $sourcePath = $sourceDir.FullName
        $gradleProperties = Join-Path $sourcePath "gradle.properties"
        $versionLine = Get-Content -Path $gradleProperties | Where-Object { $_ -like "version=*" } | Select-Object -First 1
        $sourceVersion = $versionLine.Substring("version=".Length)
        if ($sourceVersion -ne $floodgateSourceVersion) {
            throw "Floodgate source version mismatch; expected $floodgateSourceVersion, got $sourceVersion"
        }

        Write-Host "Building official Floodgate commit with BUILD_NUMBER=$floodgateBuild..."
        & docker run --rm `
            -e "BUILD_NUMBER=$floodgateBuild" `
            -e "GRADLE_USER_HOME=/tmp/gradle-home" `
            -v "${sourcePath}:/workspace" `
            -w /workspace `
            $floodgateGradleImage `
            gradle :velocity:build --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Floodgate Gradle build failed with exit code $LASTEXITCODE"
        }

        $builtJar = Join-Path $sourcePath "velocity/build/libs/floodgate-velocity.jar"
        if (-not (Test-Path $builtJar)) {
            throw "Floodgate build did not produce velocity/build/libs/floodgate-velocity.jar"
        }
        Copy-Item -Force -Path $builtJar -Destination $outputFile
        $builtSha = (Get-FileHash -Path $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
        @(
            "source=https://github.com/GeyserMC/Floodgate",
            "commit=$floodgateCommit",
            "source_version=$floodgateSourceVersion",
            "runtime_version=$floodgateVersion",
            "build=$floodgateBuild",
            "sha256=$builtSha"
        ) | Set-Content -Path $provenanceFile -Encoding Ascii
        Write-Host "Built Floodgate $floodgateVersion build $floodgateBuild from commit $floodgateCommit sha256=$builtSha"
    }
    finally {
        if (Test-Path $sourceZip) {
            Remove-Item -Force $sourceZip
        }
        if (Test-Path $extractRoot) {
            Remove-Item -Recurse -Force $extractRoot
        }
    }
}

Get-GeyserVelocity
Build-FloodgateVelocity

Write-Host "Pinned crossplay plugins are ready in $outputDir"
