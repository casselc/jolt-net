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
#>
param(
  [string]$JoltNetPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$RuntimePath = "D:\src\jolt-proposal-net-runtime",
  [string]$ChezExe = "D:\chez-10.4.1\bin\scheme.exe"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ChezExe)) {
  throw "test-windows-blocking.ps1: scheme.exe not found at $ChezExe"
}
if (-not (Test-Path (Join-Path $RuntimePath "host\chez\cli.ss"))) {
  throw "test-windows-blocking.ps1: host\chez\cli.ss not found under $RuntimePath"
}

$env:JOLT_PWD = $JoltNetPath
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"
$env:JOLT_SH = "C:\Program Files\Git\bin\sh.exe"

Write-Host "jolt-net blocking suite"
Write-Host "  JOLT_PWD      = $env:JOLT_PWD"
Write-Host "  runtime       = $RuntimePath"
Write-Host "  scheme.exe    = $ChezExe"
Write-Host ""

Push-Location $RuntimePath
try {
  & $ChezExe --script "host\chez\cli.ss" "-M:blocking-test"
  $exitCode = $LASTEXITCODE
}
finally {
  Pop-Location
}

exit $exitCode
