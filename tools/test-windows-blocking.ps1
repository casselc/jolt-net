#requires -version 5
<#
.SYNOPSIS
  Run jolt-net's dependency-free blocking-socket suite on native Windows.

.DESCRIPTION
  Invokes the proposal Jolt runtime's Chez Scheme entry point directly against
  this checkout's -M:blocking-test alias. Deliberately does NOT go through
  bash, bin/jnc, or any shell wrapper: the ordinary -M:test alias currently
  resolves the jolt-hegel Git dependency before running anything, which hits a
  separate, out-of-scope Windows Git-command problem in the core fork, and
  that resolution path is exactly what -M:blocking-test exists to avoid.

  Must be invoked from a native Windows context (PowerShell or WSL calling
  powershell.exe -File), never from a WSL UNC current directory -- Set-Location
  below always lands in a native D:\ path before scheme.exe runs.

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
  one also bounds failures before that main starts.
#>
param(
  [string]$JoltNetPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$RuntimePath = "D:\src\jolt-proposal-net-runtime",
  [string]$ChezExe = "D:\chez-10.4.1\bin\scheme.exe",
  [string]$ShellExe = "C:\Program Files\Git\bin\sh.exe",
  [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ChezExe)) {
  throw "test-windows-blocking.ps1: scheme.exe not found at $ChezExe"
}
if (-not (Test-Path (Join-Path $RuntimePath "host\chez\cli.ss"))) {
  throw "test-windows-blocking.ps1: host\chez\cli.ss not found under $RuntimePath"
}
if ($TimeoutSeconds -le 0) {
  throw "test-windows-blocking.ps1: TimeoutSeconds must be positive"
}

$env:JOLT_PWD = $JoltNetPath
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"
$env:JOLT_SH = $ShellExe

Write-Host "jolt-net blocking suite"
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
    -ArgumentList @("--script", "host\chez\cli.ss", "-M:blocking-test") `
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
      "test-windows-blocking.ps1: timed out after $TimeoutSeconds seconds; terminating PID $($process.Id)"
    )
    try {
      $process.Kill()
      $process.WaitForExit()
    }
    catch {
      [Console]::Error.WriteLine(
        "test-windows-blocking.ps1: failed to terminate timed-out PID $($process.Id): $($_.Exception.Message)"
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
