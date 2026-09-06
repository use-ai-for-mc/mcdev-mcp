$ErrorActionPreference = "Stop"

$npxTimeoutMilliseconds = 10 * 60 * 1000
$processStopTimeoutMilliseconds = 10 * 1000
$harnessStopTimeoutMilliseconds = 30 * 1000
$harnessReadinessTimeoutMilliseconds = 30 * 1000
$portCloseTimeoutMilliseconds = 10 * 1000
$conformancePort = 3000
$shutdownRoot = [IO.Path]::GetFullPath(
    [IO.Path]::Combine((Get-Location).Path, "build", "tmp", "conformance")
)
$shutdownDirectory = [IO.Path]::Combine($shutdownRoot, [Guid]::NewGuid().ToString("N"))
$shutdownFile = [IO.Path]::Combine($shutdownDirectory, "stop.shutdown")
$conformanceExitCode = 1

function Build-ConformanceHarness {
    $gradle = [IO.Path]::GetFullPath([IO.Path]::Combine((Get-Location).Path, "gradlew.bat"))
    & $gradle "conformanceHarnessJar" "--no-daemon" "--console=plain" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build the conformance harness (Gradle exit code $LASTEXITCODE)."
    }

    $harnessJar = [IO.Path]::GetFullPath(
        [IO.Path]::Combine((Get-Location).Path, "build", "conformance", "mcdev-mcp-conformance.jar")
    )
    if (-not [IO.File]::Exists($harnessJar)) {
        throw "Gradle completed without producing the conformance harness at $harnessJar."
    }

    $javaExecutableFile = [IO.Path]::GetFullPath(
        [IO.Path]::Combine((Get-Location).Path, "build", "conformance", "java-executable.txt")
    )
    if (-not [IO.File]::Exists($javaExecutableFile)) {
        throw "Gradle completed without recording the conformance Java executable at $javaExecutableFile."
    }
    $javaExecutable = [IO.File]::ReadAllText($javaExecutableFile, [Text.Encoding]::UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($javaExecutable) -or -not [IO.File]::Exists($javaExecutable)) {
        throw "Gradle recorded an invalid conformance Java executable: $javaExecutable"
    }

    return [PSCustomObject]@{
        HarnessJar = $harnessJar
        JavaExecutable = $javaExecutable
    }
}

function Start-ConformanceHarness {
    param(
        [Parameter(Mandatory)]
        [string] $HarnessJar,

        [Parameter(Mandatory)]
        [string] $JavaExecutable
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $JavaExecutable
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $false
    $startInfo.RedirectStandardError = $false
    $startInfo.ArgumentList.Add("--add-opens=java.base/java.lang=ALL-UNNAMED")
    $startInfo.ArgumentList.Add("--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED")
    $startInfo.ArgumentList.Add("-Ddev.mcdevmcp.conformance.shutdownFile=$shutdownFile")
    $startInfo.ArgumentList.Add("-jar")
    $startInfo.ArgumentList.Add($HarnessJar)

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void] $process.Start()
        return $process
    } catch {
        $process.Dispose()
        throw
    }
}

function Stop-ProcessTree {
    param(
        [Parameter(Mandatory)]
        [System.Diagnostics.Process] $Process,

        [Parameter(Mandatory)]
        [string] $Description,

        [Parameter(Mandatory)]
        [int] $TimeoutMilliseconds
    )

    $killFailure = $null
    try {
        if (-not $Process.HasExited) {
            $taskkill = Start-Process -FilePath "$env:SystemRoot\System32\taskkill.exe" -ArgumentList "/PID", "$($Process.Id)", "/T", "/F" -PassThru -Wait -NoNewWindow
            try {
                if ($taskkill.ExitCode -ne 0 -and -not $Process.HasExited) {
                    $killFailure = "Failed to terminate the $Description process tree (taskkill exit code $($taskkill.ExitCode))."
                }
            } finally {
                $taskkill.Dispose()
            }
        }
    } catch {
        if (-not $Process.HasExited) {
            $killFailure = "Failed to terminate the $Description process tree: $($_.Exception.Message)"
        }
    }

    try {
        if (-not $Process.WaitForExit($TimeoutMilliseconds)) {
            return "$Description did not stop within $($TimeoutMilliseconds / 1000) seconds."
        }
    } catch {
        return "Failed while waiting for $Description to stop: $($_.Exception.Message)"
    }

    return $killFailure
}

function Test-ConformancePortOpen {
    $client = $null
    try {
        $client = [Net.Sockets.TcpClient]::new()
        $client.Connect("127.0.0.1", $conformancePort)
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } finally {
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Wait-ConformancePortClosed {
    param(
        [Parameter(Mandatory)]
        [int] $TimeoutMilliseconds
    )

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
        if (-not (Test-ConformancePortOpen)) {
            return $true
        }
        Start-Sleep -Milliseconds 100
    }
    return -not (Test-ConformancePortOpen)
}

function Invoke-NpxConformance {
    param(
        [Parameter(Mandatory)]
        [System.Diagnostics.Process] $Harness
    )

    $npxCommand = Get-Command "npx.cmd" -CommandType Application | Select-Object -First 1
    if ($null -eq $npxCommand) {
        throw "npx.cmd was not found on PATH."
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $env:ComSpec
    $startInfo.Arguments = '/d /s /c ""{0}" --yes @modelcontextprotocol/conformance@0.1.16 server --url http://127.0.0.1:3000/mcp --suite active"' -f $npxCommand.Source
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $false
    $startInfo.RedirectStandardError = $false

    $npx = [System.Diagnostics.Process]::new()
    $npx.StartInfo = $startInfo
    $started = $false
    $failure = $null
    $cleanupFailure = $null
    $exitCode = 1
    $terminateTree = $false

    try {
        [void] $npx.Start()
        $started = $true
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

        while (-not $npx.HasExited) {
            if ($Harness.HasExited) {
                $failure = "Conformance harness stopped while the official runner was active."
                $terminateTree = $true
                break
            }

            $remainingMilliseconds = $npxTimeoutMilliseconds - $stopwatch.ElapsedMilliseconds
            if ($remainingMilliseconds -le 0) {
                $failure = "Official MCP conformance exceeded its 10-minute hard deadline."
                $terminateTree = $true
                break
            }

            $waitMilliseconds = [Math]::Min(250, [int] $remainingMilliseconds)
            [void] $npx.WaitForExit($waitMilliseconds)
        }

        if ($null -eq $failure) {
            $exitCode = $npx.ExitCode
            $terminateTree = $exitCode -ne 0
        }
    } catch {
        $failure = "Official MCP conformance runner failed: $($_.Exception.Message)"
        $terminateTree = $started
    } finally {
        if ($started -and $terminateTree) {
            $cleanupParameters = @{
                Process             = $npx
                Description         = "npx conformance runner"
                TimeoutMilliseconds = $processStopTimeoutMilliseconds
            }
            $cleanupFailure = Stop-ProcessTree @cleanupParameters
        }

        $npx.Dispose()
    }

    if ($null -ne $cleanupFailure) {
        $failure = if ($null -eq $failure) {
            $cleanupFailure
        } else {
            "$failure`n$cleanupFailure"
        }
    }
    if ($null -ne $failure) {
        throw $failure
    }

    if ($exitCode -ne 0) {
        [Console]::Error.WriteLine("Official MCP conformance exited with code $exitCode.")
    }
    return $exitCode
}

if (Test-ConformancePortOpen) {
    throw "Conformance port $conformancePort is already in use."
}

$harnessBuild = Build-ConformanceHarness
if (Test-ConformancePortOpen) {
    throw "Conformance port $conformancePort became occupied while the harness was being built."
}

$harness = $null
try {
    [void] [IO.Directory]::CreateDirectory($shutdownDirectory)
    $harness = Start-ConformanceHarness `
        -HarnessJar $harnessBuild.HarnessJar `
        -JavaExecutable $harnessBuild.JavaExecutable
    $deadline = [DateTime]::UtcNow.AddMilliseconds($harnessReadinessTimeoutMilliseconds)
    $portOpen = $false
    do {
        if (Test-ConformancePortOpen) {
            $portOpen = $true
            break
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline -and -not $harness.HasExited)

    if ($harness.HasExited) {
        throw "Conformance harness stopped with exit code $($harness.ExitCode) before opening port $conformancePort."
    }
    if (-not $portOpen) {
        throw "Conformance harness did not open port $conformancePort within $($harnessReadinessTimeoutMilliseconds / 1000) seconds after the JVM was started."
    }

    $conformanceExitCode = Invoke-NpxConformance -Harness $harness
} finally {
    $harnessCleanupFailure = $null
    if ($null -ne $harness) {
        try {
            [IO.File]::WriteAllText($shutdownFile, "stop", [Text.UTF8Encoding]::new($false))
            if (-not $harness.HasExited) {
                if (-not $harness.WaitForExit($harnessStopTimeoutMilliseconds)) {
                    $harnessCleanupFailure = "Conformance harness did not stop within $($harnessStopTimeoutMilliseconds / 1000) seconds after its shutdown signal."
                }
            }
        } catch {
            $harnessCleanupFailure = "Failed to signal or await the conformance harness: $($_.Exception.Message)"
        }

        if (-not $harness.HasExited) {
            $forcedCleanupParameters = @{
                Process             = $harness
                Description         = "conformance harness"
                TimeoutMilliseconds = $processStopTimeoutMilliseconds
            }
            $forcedCleanupFailure = Stop-ProcessTree @forcedCleanupParameters
            if ($null -ne $forcedCleanupFailure) {
                $harnessCleanupFailure = if ($null -eq $harnessCleanupFailure) {
                    $forcedCleanupFailure
                } else {
                    "$harnessCleanupFailure`n$forcedCleanupFailure"
                }
            }
        }

        $harness.Dispose()
    }

    if (-not (Wait-ConformancePortClosed -TimeoutMilliseconds $portCloseTimeoutMilliseconds)) {
        $portCloseFailure = "Conformance port $conformancePort did not close within $($portCloseTimeoutMilliseconds / 1000) seconds."
        $harnessCleanupFailure = if ($null -eq $harnessCleanupFailure) {
            $portCloseFailure
        } else {
            "$harnessCleanupFailure`n$portCloseFailure"
        }
    }

    $resolvedShutdownDirectory = [IO.Path]::GetFullPath($shutdownDirectory)
    $shutdownRootPrefix = $shutdownRoot.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedShutdownDirectory.StartsWith($shutdownRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        $pathFailure = "Refusing to remove conformance control directory outside $shutdownRoot."
        $harnessCleanupFailure = if ($null -eq $harnessCleanupFailure) {
            $pathFailure
        } else {
            "$harnessCleanupFailure`n$pathFailure"
        }
    } else {
        Remove-Item -LiteralPath $resolvedShutdownDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }

    if ($null -ne $harnessCleanupFailure) {
        [Console]::Error.WriteLine($harnessCleanupFailure)
        $conformanceExitCode = 1
    }
}

exit $conformanceExitCode
