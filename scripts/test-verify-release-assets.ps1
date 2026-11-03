[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$version = (Get-Content -LiteralPath (Join-Path $root "gradle.properties") |
        Where-Object { $_ -like "version=*" }).Substring(8)
$scratchRoot = Join-Path $root "build\tmp\release-verifier-test"
$source = Join-Path $scratchRoot "source"
$fixtures = Join-Path $scratchRoot "fixtures"
$imageStage = Join-Path $scratchRoot "image"
$verify = Join-Path $PSScriptRoot "verify-release-assets.ps1"
$platforms = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot "release-platforms.psd1")
$jarName = "mcdev-mcp-$version.jar"
$checksumName = "$jarName.sha256"
$expectedNames = @($jarName, $checksumName) + @($platforms.Keys | Sort-Object | ForEach-Object { "mcdev-mcp-$version-$_.mcpb"; "mcdev-mcp-$version-$_.zip" })
$releaseWorkflow = Get-Content -LiteralPath (Join-Path $root ".github\workflows\release.yml") -Raw
$publishStep = [regex]::Match(
    $releaseWorkflow,
    '(?ms)^      - name: Publish GitHub Release\r?\n(?<body>.*?)(?=^      - name:|\z)'
)
if (-not $publishStep.Success) {
    throw "Release workflow has no publish step"
}
if ($publishStep.Groups["body"].Value -notmatch '(?m)^          GH_REPO: \$\{\{ github\.repository \}\}\r?$') {
    throw "Release publish step must identify the repository explicitly"
}
if ($publishStep.Groups["body"].Value -notmatch ('(?m)^          test "\$\{#assets\[@\]\}" -eq ' + $expectedNames.Count + '\r?$')) {
    throw "Release publish step must require exactly $($expectedNames.Count) assets"
}
$verifyReleaseJob = [regex]::Match(
    $releaseWorkflow,
    '(?ms)^  verify-release:\r?\n(?<body>.*?)(?=^  [a-z0-9-]+:\r?$|\z)'
)
if (-not $verifyReleaseJob.Success -or $verifyReleaseJob.Groups["body"].Value -notmatch '(?m)^    needs: \[[^\]]*\bpackage\b[^\]]*\]\r?$') {
    throw "Release verification must wait for the package of every platform"
}

function Reset-Fixtures {
    Remove-Item -LiteralPath $fixtures -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $fixtures -Force | Out-Null
    foreach ($name in $expectedNames) {
        Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $fixtures $name)
    }
}

function Assert-Failure {
    param(
        [Parameter(Mandatory)]
        [string]$Label,
        [Parameter(Mandatory)]
        [string]$ExpectedMessage,
        [Parameter(Mandatory)]
        [scriptblock]$Action
    )

    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike $ExpectedMessage) {
            throw "$Label produced the wrong failure: $($_.Exception.Message)"
        }
        return
    }
    throw "$Label unexpectedly passed"
}

# Builds the archive of one platform the way the packaging jobs lay it out: the launcher and the
# release JAR inside the application image, and for a bundle the manifest generated for it.
function New-ImageFixture {
    param(
        [Parameter(Mandatory)]
        [string]$Platform,
        [Parameter(Mandatory)]
        [ValidateSet("zip", "mcpb")]
        [string]$Kind,
        [Parameter(Mandatory)]
        [string]$InnerJar,
        [string]$ManifestVersion = $version,
        [string]$ManifestPlatform = $Platform,
        [string[]]$ExtraFiles = @()
    )

    $layout = $platforms[$Platform]
    Remove-Item -LiteralPath $imageStage -Recurse -Force -ErrorAction SilentlyContinue
    $files = @{}
    $files[$layout.Launcher] = "launcher"
    foreach ($file in $ExtraFiles) {
        $files[$file] = "unexpected"
    }
    foreach ($file in $files.Keys) {
        $path = Join-Path $imageStage $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        Set-Content -LiteralPath $path -Value $files[$file] -NoNewline
    }
    $application = Join-Path $imageStage $layout.Application
    New-Item -ItemType Directory -Path $application -Force | Out-Null
    Copy-Item -LiteralPath $InnerJar -Destination (Join-Path $application $jarName)
    if ($Kind -eq "mcpb") {
        $manifest = Get-Content -LiteralPath (Join-Path $root "build\mcpb\$($platforms[$ManifestPlatform].Manifest)") -Raw | ConvertFrom-Json
        $manifest.version = $ManifestVersion
        $manifest | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $imageStage "manifest.json")
    }
    $archive = Join-Path $fixtures "mcdev-mcp-$version-$Platform.$Kind"
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::CreateFromDirectory($imageStage, $archive, [System.IO.Compression.CompressionLevel]::Fastest, $false)
}

function New-JarWithVersion {
    param(
        [Parameter(Mandatory)]
        [string]$Output,
        [Parameter(Mandatory)]
        [string]$ImplementationVersion
    )

    $jarStage = Join-Path $scratchRoot "jar"
    Remove-Item -LiteralPath $jarStage -Recurse -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $source $jarName), $jarStage)
    $manifestPath = Join-Path $jarStage "META-INF\MANIFEST.MF"
    $manifest = Get-Content -LiteralPath $manifestPath -Raw
    $manifest = [regex]::Replace($manifest, "(?m)^Implementation-Version: .*\r?$", "Implementation-Version: $ImplementationVersion")
    [IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $Output -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::CreateFromDirectory($jarStage, $Output)
}

Remove-Item -LiteralPath $scratchRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $source -Force | Out-Null
foreach ($name in @($jarName, $checksumName)) {
    $built = Join-Path $root "build\distributions\$name"
    if (-not (Test-Path -LiteralPath $built -PathType Leaf)) {
        throw "Release verifier fixture is unavailable: $name"
    }
    Copy-Item -LiteralPath $built -Destination (Join-Path $source $name)
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$releaseJar = Join-Path $source $jarName
New-Item -ItemType Directory -Path $fixtures -Force | Out-Null
foreach ($platform in $platforms.Keys) {
    foreach ($kind in @("zip", "mcpb")) {
        New-ImageFixture -Platform $platform -Kind $kind -InnerJar $releaseJar
        $name = "mcdev-mcp-$version-$platform.$kind"
        Copy-Item -LiteralPath (Join-Path $fixtures $name) -Destination (Join-Path $source $name)
    }
}
$windowsBundle = "mcdev-mcp-$version-windows-x64.mcpb"
$linuxImage = "mcdev-mcp-$version-linux-x64.zip"

Reset-Fixtures
& $verify -DryRun -Version $version -Directory $fixtures | Out-Null

Remove-Item -LiteralPath (Join-Path $fixtures $windowsBundle)
Assert-Failure -Label "missing asset" -ExpectedMessage "Missing release asset: $windowsBundle" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
Set-Content -LiteralPath (Join-Path $fixtures "unexpected.txt") -Value "unexpected" -NoNewline
Assert-Failure -Label "unexpected asset" -ExpectedMessage "Unexpected release asset: unexpected.txt" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
$badChecksum = ("0" * 64) + "  $jarName"
Set-Content -LiteralPath (Join-Path $fixtures $checksumName) -Value $badChecksum -NoNewline
Assert-Failure -Label "bad checksum" -ExpectedMessage "JAR SHA-256 does not match checksum file" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
Assert-Failure -Label "prefixed version" -ExpectedMessage "Release version must be an unprefixed semantic version: v$version" -Action {
    & $verify -DryRun -Version "v$version" -Directory $fixtures
}

Reset-Fixtures
$wrongVersionJar = Join-Path $scratchRoot "wrong-version.jar"
New-JarWithVersion -Output $wrongVersionJar -ImplementationVersion "999.0.0"
Copy-Item -LiteralPath $wrongVersionJar -Destination (Join-Path $fixtures $jarName) -Force
$wrongJarHash = (Get-FileHash -LiteralPath (Join-Path $fixtures $jarName) -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $fixtures $checksumName) -Value "$wrongJarHash  $jarName" -NoNewline
Assert-Failure -Label "JAR implementation version" -ExpectedMessage "JAR Implementation-Version does not match release version" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "windows-x64" -Kind mcpb -InnerJar $releaseJar -ManifestVersion "999.0.0"
Assert-Failure -Label "MCPB manifest version" -ExpectedMessage "$windowsBundle version does not match release version" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "windows-x64" -Kind mcpb -InnerJar $wrongVersionJar
Assert-Failure -Label "MCPB inner JAR" -ExpectedMessage "$windowsBundle inner JAR SHA-256 does not match release JAR" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "linux-x64" -Kind zip -InnerJar $wrongVersionJar
Assert-Failure -Label "image inner JAR" -ExpectedMessage "$linuxImage inner JAR SHA-256 does not match release JAR" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "windows-x64" -Kind mcpb -InnerJar $releaseJar -ManifestPlatform "linux-x64"
Assert-Failure -Label "MCPB of another system" -ExpectedMessage "$windowsBundle is not declared for win32 alone" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "linux-x64" -Kind zip -InnerJar $releaseJar -ExtraFiles @("mcdev-mcp/lib/app/mcdev-mcp.aot")
Assert-Failure -Label "shipped startup cache" -ExpectedMessage "$linuxImage ships a startup cache" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-ImageFixture -Platform "windows-x64" -Kind mcpb -InnerJar $releaseJar -ExtraFiles @("stray.txt")
Assert-Failure -Label "file outside the image" -ExpectedMessage "$windowsBundle holds files outside the application image: stray.txt" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Remove-Item -LiteralPath $scratchRoot -Recurse -Force
Write-Output "Release verifier regression tests passed"
