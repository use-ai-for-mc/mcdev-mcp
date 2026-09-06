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
$verify = Join-Path $PSScriptRoot "verify-release-assets.ps1"
$expectedNames = @("mcdev-mcp-$version.jar", "mcdev-mcp-$version.jar.sha256", "mcdev-mcp-$version.mcpb")
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
$parityJob = [regex]::Match(
    $releaseWorkflow,
    '(?ms)^  parity:\r?\n(?<body>.*?)(?=^  [a-z0-9-]+:\r?$|\z)'
)
if (-not $parityJob.Success -or $parityJob.Groups["body"].Value -notmatch '(?m)^        run: ./gradlew parityTest ') {
    throw "Release workflow must run the frozen Node parity suite"
}
$verifyReleaseJob = [regex]::Match(
    $releaseWorkflow,
    '(?ms)^  verify-release:\r?\n(?<body>.*?)(?=^  [a-z0-9-]+:\r?$|\z)'
)
if (-not $verifyReleaseJob.Success -or $verifyReleaseJob.Groups["body"].Value -notmatch '(?m)^    needs: \[[^\]]*\bparity\b[^\]]*\]\r?$') {
    throw "Release verification must wait for frozen Node parity"
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

function New-McpbFixture {
    param(
        [Parameter(Mandatory)]
        [string]$ManifestVersion,
        [Parameter(Mandatory)]
        [string]$InnerJar
    )

    Remove-Item -LiteralPath $mcpbStage -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $mcpbStage -Force | Out-Null
    $manifest = Get-Content -LiteralPath (Join-Path $root "build\mcpb\manifest.json") -Raw | ConvertFrom-Json
    $manifest.version = $ManifestVersion
    $manifest | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $mcpbStage "manifest.json")
    Copy-Item -LiteralPath (Join-Path $root "packaging\mcpb\bootstrap.cjs") -Destination (Join-Path $mcpbStage "bootstrap.cjs")
    Copy-Item -LiteralPath $InnerJar -Destination (Join-Path $mcpbStage "mcdev-mcp.jar")
    $archive = Join-Path $fixtures $expectedNames[2]
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::CreateFromDirectory($mcpbStage, $archive)
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
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $source $expectedNames[0]), $jarStage)
    $manifestPath = Join-Path $jarStage "META-INF\MANIFEST.MF"
    $manifest = Get-Content -LiteralPath $manifestPath -Raw
    $manifest = [regex]::Replace($manifest, "(?m)^Implementation-Version: .*\r?$", "Implementation-Version: $ImplementationVersion")
    [IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $Output -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::CreateFromDirectory($jarStage, $Output)
}

Remove-Item -LiteralPath $scratchRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $source -Force | Out-Null
foreach ($name in $expectedNames[0..1]) {
    $built = Join-Path $root "build\distributions\$name"
    if (-not (Test-Path -LiteralPath $built -PathType Leaf)) {
        throw "Release verifier fixture is unavailable: $name"
    }
    Copy-Item -LiteralPath $built -Destination (Join-Path $source $name)
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$mcpbStage = Join-Path $scratchRoot "mcpb"
New-Item -ItemType Directory -Path $fixtures -Force | Out-Null
New-McpbFixture -ManifestVersion $version -InnerJar (Join-Path $source $expectedNames[0])
Copy-Item -LiteralPath (Join-Path $fixtures $expectedNames[2]) -Destination (Join-Path $source $expectedNames[2])

Reset-Fixtures
& $verify -DryRun -Version $version -Directory $fixtures | Out-Null

Remove-Item -LiteralPath (Join-Path $fixtures $expectedNames[2])
Assert-Failure -Label "missing asset" -ExpectedMessage "Missing release asset: $($expectedNames[2])" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
Set-Content -LiteralPath (Join-Path $fixtures "unexpected.txt") -Value "unexpected" -NoNewline
Assert-Failure -Label "unexpected asset" -ExpectedMessage "Unexpected release asset: unexpected.txt" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
$badChecksum = ("0" * 64) + "  mcdev-mcp-$version.jar"
Set-Content -LiteralPath (Join-Path $fixtures $expectedNames[1]) -Value $badChecksum -NoNewline
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
Copy-Item -LiteralPath $wrongVersionJar -Destination (Join-Path $fixtures $expectedNames[0]) -Force
$wrongJarHash = (Get-FileHash -LiteralPath (Join-Path $fixtures $expectedNames[0]) -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $fixtures $expectedNames[1]) -Value "$wrongJarHash  $($expectedNames[0])" -NoNewline
Assert-Failure -Label "JAR implementation version" -ExpectedMessage "JAR Implementation-Version does not match release version" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-McpbFixture -ManifestVersion "999.0.0" -InnerJar (Join-Path $source $expectedNames[0])
Assert-Failure -Label "MCPB manifest version" -ExpectedMessage "MCPB version does not match release version" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Reset-Fixtures
New-McpbFixture -ManifestVersion $version -InnerJar $wrongVersionJar
Assert-Failure -Label "MCPB inner JAR" -ExpectedMessage "MCPB inner JAR SHA-256 does not match release JAR" -Action {
    & $verify -DryRun -Version $version -Directory $fixtures
}

Remove-Item -LiteralPath $scratchRoot -Recurse -Force
Write-Output "Release verifier regression tests passed"
