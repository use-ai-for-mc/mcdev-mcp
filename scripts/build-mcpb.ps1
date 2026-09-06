[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Jar,
    [switch]$SkipBuild,
    [string]$Manifest,
    [string]$Checksum
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "Shaded JAR does not exist: $Jar"
}
$jarPath = (Resolve-Path $Jar).Path
$packaging = Join-Path $root "packaging\mcpb"
$staging = Join-Path $root "build\mcpb\stage"
$stagingManifest = Join-Path $root "build\mcpb\manifest.json"
$distribution = Join-Path $root "build\distributions"

if (-not $SkipBuild) {
    & (Join-Path $root "gradlew.bat") shadowJar generateMcpbManifest generateJarChecksum --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "generateMcpbManifest failed"
    }
}
if ($SkipBuild) {
    if ([string]::IsNullOrWhiteSpace($Manifest)) {
        throw "-SkipBuild requires -Manifest from the Java 26 build artifact"
    }
    if ([string]::IsNullOrWhiteSpace($Checksum)) {
        throw "-SkipBuild requires -Checksum from the Java 26 build artifact"
    }
    $stagingManifest = (Resolve-Path -LiteralPath $Manifest).Path
}
if (-not (Test-Path -LiteralPath $stagingManifest -PathType Leaf)) {
    throw "Generated staging manifest does not exist: $stagingManifest"
}
$generatedManifest = Get-Content -LiteralPath $stagingManifest -Raw | ConvertFrom-Json
$bundle = Join-Path $distribution ("mcdev-mcp-" + $generatedManifest.version + ".mcpb")
$expectedJar = Join-Path $root ("build\libs\mcdev-mcp-" + $generatedManifest.version + ".jar")
if (-not $SkipBuild -and $jarPath -ne (Resolve-Path $expectedJar).Path) {
    throw "MCPB packaging requires the exact shadowJar output: $expectedJar"
}
if ((Split-Path -Leaf $jarPath) -ne ("mcdev-mcp-" + $generatedManifest.version + ".jar")) {
    throw "MCPB packaging received an unexpected JAR filename"
}

$checksumPath = if ([string]::IsNullOrWhiteSpace($Checksum)) {
    Join-Path $distribution ("mcdev-mcp-" + $generatedManifest.version + ".jar.sha256")
} else {
    (Resolve-Path -LiteralPath $Checksum).Path
}
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "JAR checksum does not exist: $checksumPath"
}
$checksumText = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
$expectedChecksumName = "mcdev-mcp-" + $generatedManifest.version + ".jar"
$checksumPattern = "^([0-9a-fA-F]{64})  " + [regex]::Escape($expectedChecksumName) + "$"
if ($checksumText -notmatch $checksumPattern) {
    throw "JAR checksum must contain the exact SHA-256 and filename: $expectedChecksumName"
}
$recordedHash = $Matches[1].ToLowerInvariant()

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archiveForManifest = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $manifestEntry = $archiveForManifest.GetEntry("META-INF/MANIFEST.MF")
    if ($null -eq $manifestEntry) {
        throw "Shaded JAR has no manifest"
    }
    $manifestReader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try {
        $manifestText = $manifestReader.ReadToEnd()
    } finally {
        $manifestReader.Dispose()
    }
} finally {
    $archiveForManifest.Dispose()
}
if ($manifestText -notmatch "(?m)^Main-Class: dev\.mcdevmcp\.app\.Main\r?$") {
    throw "Shaded JAR has an unexpected Main-Class"
}
if ($manifestText -notmatch ("(?m)^Implementation-Version: " + [regex]::Escape($generatedManifest.version) + "\r?$")) {
    throw "Shaded JAR implementation version does not match the generated MCPB version"
}

$sourceHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $recordedHash) {
    throw "Supplied shaded JAR SHA-256 does not match its checksum"
}
Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $staging -Force | Out-Null
Copy-Item -LiteralPath $stagingManifest -Destination (Join-Path $staging "manifest.json")
Copy-Item -LiteralPath (Join-Path $packaging "bootstrap.cjs") -Destination (Join-Path $staging "bootstrap.cjs")
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $staging "mcdev-mcp.jar")

$stagedHash = (Get-FileHash -LiteralPath (Join-Path $staging "mcdev-mcp.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $stagedHash) {
    throw "Staged JAR SHA-256 does not match the supplied shaded JAR"
}

Push-Location $packaging
try {
    npm ci --ignore-scripts
    if ($LASTEXITCODE -ne 0) {
        throw "npm ci failed"
    }
    New-Item -ItemType Directory -Path $distribution -Force | Out-Null
    & (Join-Path $packaging "node_modules\.bin\mcpb.cmd") pack $staging $bundle
    if ($LASTEXITCODE -ne 0) {
        throw "mcpb pack failed"
    }
} finally {
    Pop-Location
}

$extract = Join-Path $root "build\mcpb\extract"
$archive = Join-Path $root "build\mcpb\bundle.zip"
Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $bundle -Destination $archive -Force
Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
$innerJar = Join-Path $extract "mcdev-mcp.jar"
$innerHash = (Get-FileHash -LiteralPath $innerJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $innerHash) {
    throw "MCPB inner JAR SHA-256 does not match the supplied shaded JAR"
}

if ($env:MCDEV_MCP_SKIP_SMOKE -ne "1") {
    if ($SkipBuild) {
        $java = Join-Path $env:JAVA_HOME "bin\java.exe"
        & $java -cp $jarPath dev.mcdevmcp.packaging.McpbBundleSmokeMain $extract
    } else {
        & (Join-Path $root "gradlew.bat") mcpbBundleSmoke "-PmcpbBundleDirectory=$extract" --console=plain --no-configuration-cache
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Extracted MCPB initialize/tools-list smoke failed"
    }
}

Write-Output "Built $bundle with SHA-256 $sourceHash"
