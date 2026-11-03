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
$platforms = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot "release-platforms.psd1")
$platformNames = @($platforms.Keys | Sort-Object)
$jarName = "mcdev-mcp-$Version.jar"
$expectedNames = @($jarName, "$jarName.sha256") + @($platformNames | ForEach-Object { "mcdev-mcp-$Version-$_.mcpb"; "mcdev-mcp-$Version-$_.zip" })
$actualNames = @(Get-ChildItem -LiteralPath $directoryPath | Select-Object -ExpandProperty Name | Sort-Object)
$missingNames = @($expectedNames | Where-Object { $_ -cnotin $actualNames })
$unexpectedNames = @($actualNames | Where-Object { $_ -cnotin $expectedNames })
if ($missingNames.Count -gt 0) {
    throw "Missing release asset: $($missingNames -join ', ')"
}
if ($unexpectedNames.Count -gt 0) {
    throw "Unexpected release asset: $($unexpectedNames -join ', ')"
}

$jar = Join-Path $directoryPath $jarName
$checksumText = (Get-Content -LiteralPath (Join-Path $directoryPath "$jarName.sha256") -Raw).Trim()
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

# An archive must hold the application image of its platform around the exact release JAR and
# nothing else. A startup cache in particular is only valid where it was recorded.
function Assert-ApplicationImage {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [hashtable]$Layout,
        [switch]$Bundle
    )

    $name = Split-Path -Leaf $Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $files = @($archive.Entries | Select-Object -ExpandProperty FullName | Where-Object { -not $_.EndsWith("/") })
        $outside = @($files | Where-Object { -not $_.StartsWith($Layout.Root, [StringComparison]::Ordinal) -and -not ($Bundle -and $_ -ceq "manifest.json") })
        if ($outside.Count -gt 0) {
            throw "$name holds files outside the application image: $($outside -join ', ')"
        }
        if (@($files | Where-Object { $_ -like "*/mcdev-mcp.aot*" }).Count -gt 0) {
            throw "$name ships a startup cache"
        }
        if ($Layout.Launcher -cnotin $files) {
            throw "$name has no launcher at $($Layout.Launcher)"
        }
        $innerJar = $archive.GetEntry($Layout.Application + $jarName)
        if ($null -eq $innerJar) {
            throw "$name has no release JAR at $($Layout.Application + $jarName)"
        }
        $stream = $innerJar.Open()
        try {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $innerHash = ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
        if ($innerHash -cne $jarHash) {
            throw "$name inner JAR SHA-256 does not match release JAR"
        }
        if ($Bundle) {
            $manifestEntry = $archive.GetEntry("manifest.json")
            if ($null -eq $manifestEntry) { throw "$name has no manifest.json" }
            $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
            try { $bundleManifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($bundleManifest.version -cne $Version) {
                throw "$name version does not match release version"
            }
            if (($bundleManifest.compatibility.platforms -join ',') -cne $Layout.System) {
                throw "$name is not declared for $($Layout.System) alone"
            }
            $server = $bundleManifest.server
            if ($server.type -cne "binary" -or $server.entry_point -cne $Layout.Launcher -or $server.mcp_config.command -cne ('${__dirname}/' + $Layout.Launcher)) {
                throw "$name does not start the launcher of its application image"
            }
        }
    } finally { $archive.Dispose() }
}

foreach ($platform in $platformNames) {
    Assert-ApplicationImage -Path (Join-Path $directoryPath "mcdev-mcp-$Version-$platform.zip") -Layout $platforms[$platform]
    Assert-ApplicationImage -Path (Join-Path $directoryPath "mcdev-mcp-$Version-$platform.mcpb") -Layout $platforms[$platform] -Bundle
}

$mode = if ($DryRun) { "dry run" } else { "release" }
Write-Output "Verified $mode assets for $Version"
