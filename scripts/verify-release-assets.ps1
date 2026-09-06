[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Version,
    [Parameter(Mandatory)]
    [string]$Directory,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "Release version must be an unprefixed semantic version: $Version"
}
if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
    throw "Release directory does not exist: $Directory"
}
$directoryPath = (Resolve-Path -LiteralPath $Directory).Path
$expectedNames = @("mcdev-mcp-$Version.jar", "mcdev-mcp-$Version.jar.sha256", "mcdev-mcp-$Version.mcpb")
$actualNames = @(Get-ChildItem -LiteralPath $directoryPath | Select-Object -ExpandProperty Name | Sort-Object)
$missingNames = @($expectedNames | Where-Object { $_ -cnotin $actualNames })
$unexpectedNames = @($actualNames | Where-Object { $_ -cnotin $expectedNames })
if ($missingNames.Count -gt 0) {
    throw "Missing release asset: $($missingNames -join ', ')"
}
if ($unexpectedNames.Count -gt 0) {
    throw "Unexpected release asset: $($unexpectedNames -join ', ')"
}

$jar = Join-Path $directoryPath $expectedNames[0]
$checksum = Join-Path $directoryPath $expectedNames[1]
$mcpb = Join-Path $directoryPath $expectedNames[2]
$checksumText = (Get-Content -LiteralPath $checksum -Raw).Trim()
if ($checksumText -notmatch "^([0-9a-f]{64})  mcdev-mcp-" + [regex]::Escape($Version) + "\.jar$") {
    throw "Checksum must contain the exact SHA-256 and JAR filename"
}
$recordedHash = $Matches[1]
$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarHash -cne $recordedHash) {
    throw "JAR SHA-256 does not match checksum file"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarArchive = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $manifest = $jarArchive.GetEntry("META-INF/MANIFEST.MF")
    if ($null -eq $manifest) { throw "JAR has no manifest" }
    $reader = [System.IO.StreamReader]::new($manifest.Open())
    try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($manifestText -notmatch ("(?m)^Implementation-Version: " + [regex]::Escape($Version) + "\r?$")) {
        throw "JAR Implementation-Version does not match release version"
    }
} finally { $jarArchive.Dispose() }

$mcpbArchive = [System.IO.Compression.ZipFile]::OpenRead($mcpb)
try {
    $expectedMcpbEntries = @("bootstrap.cjs", "manifest.json", "mcdev-mcp.jar")
    $actualMcpbEntries = @($mcpbArchive.Entries | Select-Object -ExpandProperty FullName | Sort-Object)
    if (Compare-Object -CaseSensitive -ReferenceObject ($expectedMcpbEntries | Sort-Object) -DifferenceObject $actualMcpbEntries) {
        throw "MCPB must contain exactly: $($expectedMcpbEntries -join ', ')"
    }
    $manifestEntry = $mcpbArchive.GetEntry("manifest.json")
    $innerJar = $mcpbArchive.GetEntry("mcdev-mcp.jar")
    if ($null -eq $manifestEntry -or $null -eq $innerJar) { throw "MCPB must contain manifest.json and mcdev-mcp.jar" }
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $mcpbManifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($mcpbManifest.version -cne $Version) { throw "MCPB version does not match release version" }
    $stream = $innerJar.Open()
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $innerHash = ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
    if ($innerHash -cne $jarHash) { throw "MCPB inner JAR SHA-256 does not match release JAR" }
} finally { $mcpbArchive.Dispose() }

$mode = if ($DryRun) { "dry run" } else { "release" }
Write-Output "Verified $mode assets for $Version"
