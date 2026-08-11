#requires -version 5
<#
.SYNOPSIS
  Run jolt-net's dependency-free WSAPoll readiness suite on native Windows
  (task W3).

.DESCRIPTION
  Invokes the proposal Jolt runtime's Chez Scheme entry point directly against
  this checkout's -M:wsapoll-test alias. Deliberately does NOT go through bash,
  bin/jnc, or any shell wrapper: the ordinary -M:test alias resolves the
  jolt-hegel Git dependency before running anything, which hits a separate,
  out-of-scope Windows Git-command problem in the core fork, and avoiding that
  resolution path is exactly why the dependency-free aliases exist.

  Unlike the W1 and W2 runners, this suite DOES exercise the poller's shared
  registration and token machinery -- but over the Windows WSAPoll readiness
  backend, through the internal wake-less adapter. The public Windows poller
  remains fail-closed, and the suite asserts that as a refusal rather than
  skipping it.

  Must be invoked from a native Windows context (PowerShell, or WSL calling
  powershell.exe -File), never from a WSL UNC current directory -- the
  Set-Location below always lands in a native path before scheme.exe runs.

.PARAMETER JoltNetPath
  The jolt-net checkout under test. Defaults to this script's own repo root.

.PARAMETER RuntimePath
  The proposal Jolt runtime worktree providing host\chez\cli.ss.

.PARAMETER ChezExe
  Path to scheme.exe.

.PARAMETER ShellExe
  The POSIX `sh` the Jolt runtime shells out to. These dependency-free aliases
  resolve no Git dependency, so nothing here should reach it -- but the runtime
  reads JOLT_SH while starting, and the Git-for-Windows location is not the same
  on every image (notably the ARM64 runner). Parameterized rather than hardcoded
  so a lane can pass the path it actually verified, instead of silently handing
  the runtime a path that does not exist.

.PARAMETER TimeoutSeconds
  Outer process timeout. The Jolt test main has its own shorter watchdog; this
  one also bounds failures occurring before that main starts. A refused loopback
  connect needs roughly 2.5 seconds to become error-ready here, and the suite
  waits on several such events, so this default is larger than the W1/W2 ones.
#>
param(
  [string]$JoltNetPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$RuntimePath = "D:\src\jolt-proposal-net-runtime",
  [string]$ChezExe = "D:\chez-10.4.1\bin\scheme.exe",
  [string]$ShellExe = "C:\Program Files\Git\bin\sh.exe",
  [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ChezExe)) {
  throw "test-windows-wsapoll.ps1: scheme.exe not found at $ChezExe"
}
if (-not (Test-Path (Join-Path $RuntimePath "host\chez\cli.ss"))) {
  throw "test-windows-wsapoll.ps1: host\chez\cli.ss not found under $RuntimePath"
}
if ($TimeoutSeconds -le 0) {
  throw "test-windows-wsapoll.ps1: TimeoutSeconds must be positive"
}

# Jolt v0.7.1 joins a drive-qualified JOLT_PWD to its current directory.
# Pass the same project relative to the source-runtime checkout instead.
$runtimeUri = [Uri]((Resolve-Path $RuntimePath).Path.TrimEnd("\") + "\")
$projectUri = [Uri]((Resolve-Path $JoltNetPath).Path.TrimEnd("\") + "\")
$relativeUri = $runtimeUri.MakeRelativeUri($projectUri)
if ($relativeUri.IsAbsoluteUri) {
  throw "Jolt v0.7.1 requires JoltNetPath and RuntimePath on the same volume"
}
$env:JOLT_PWD = [Uri]::UnescapeDataString($relativeUri.ToString()).TrimEnd("/")
if (-not $env:JOLT_PWD) { $env:JOLT_PWD = "." }
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"
$env:JOLT_SH = $ShellExe

Write-Host "jolt-net WSAPoll readiness suite (task W3)"
Write-Host "  JOLT_PWD      = $env:JOLT_PWD"
Write-Host "  runtime       = $RuntimePath"
Write-Host "  scheme.exe    = $ChezExe"
Write-Host "  sh.exe        = $env:JOLT_SH"
Write-Host "  timeout       = $TimeoutSeconds seconds"
Write-Host ""

Push-Location $RuntimePath
try {
  $process = Start-Process `
    -FilePath $ChezExe `
    -ArgumentList @("--script", "host\chez\cli.ss", "-M:wsapoll-test") `
    -NoNewWindow `
    -PassThru
  # Touching .Handle forces the Process object to cache the native handle.
  # Without it, PowerShell 5.1 leaves .ExitCode EMPTY even after a successful
  # WaitForExit, so `exit $exitCode` exits 0 and a FAILING suite reports green.
  $null = $process.Handle
  if ($process.WaitForExit($TimeoutSeconds * 1000)) {
    $exitCode = $process.ExitCode
  }
  else {
    [Console]::Error.WriteLine(
      "test-windows-wsapoll.ps1: timed out after $TimeoutSeconds seconds; terminating PID $($process.Id)"
    )
    try {
      $process.Kill()
      $process.WaitForExit()
    }
    catch {
      [Console]::Error.WriteLine(
        "test-windows-wsapoll.ps1: failed to terminate timed-out PID $($process.Id): $($_.Exception.Message)"
      )
    }
    $exitCode = 124
  }
}
finally {
  Pop-Location
}

if ($null -eq $exitCode) {
  [Console]::Error.WriteLine(
    "$(Split-Path -Leaf $PSCommandPath): no exit code was observed; refusing to report success"
  )
  exit 125
}

exit $exitCode
