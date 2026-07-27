#requires -version 5
<#
.SYNOPSIS
  Execute the native Windows ARM64 ABI probe and compare its output with the
  committed descriptor evidence.

.DESCRIPTION
  The probe is a plain C program: it opens no socket and proves nothing about
  syscalls. What it does prove is that every constant, width, `sizeof`, and
  `offsetof` in tools/probed/windows-aarch64.edn was read from this machine's
  own Winsock headers by an ARM64 binary that ran here.

  Comparison is exact after normalizing line endings ONLY. The committed file is
  stored with LF because every other probed descriptor is, and a Windows
  checkout may materialize it as CRLF; nothing else about the bytes is
  forgiven. A tolerant comparison here would defeat the purpose -- a wrong
  struct offset is memory corruption, not a formatting nit.

  Both the exit code and the emitted architecture label are checked, so a probe
  that crashed after printing a plausible prefix cannot be mistaken for
  evidence.

.PARAMETER ProbeExe
  The compiled ARM64 probe. Its PE machine type must already have been asserted
  with tools\assert-arm64-image.bat; this script cannot check that itself.

.PARAMETER OutFile
  Where to write the freshly probed EDN, with LF endings, for upload.

.PARAMETER Committed
  The committed descriptor evidence to compare against. When it does not exist,
  this script reports the fresh facts and fails: an absent baseline is a review
  task, not a pass.
#>
param(
  [Parameter(Mandatory = $true)][string]$ProbeExe,
  [Parameter(Mandatory = $true)][string]$OutFile,
  [Parameter(Mandatory = $true)][string]$Committed
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ProbeExe)) {
  throw "Probe-WindowsArm64.ps1: probe executable not found at $ProbeExe"
}

$lines = & $ProbeExe
$probeExit = $LASTEXITCODE
if ($null -eq $probeExit) {
  throw "Probe-WindowsArm64.ps1: no exit code was observed from the probe; refusing to report success"
}
if ($probeExit -ne 0) {
  throw "Probe-WindowsArm64.ps1: the ABI probe exited $probeExit"
}

# Normalize to LF and guarantee exactly one trailing newline, matching how the
# POSIX and Windows x86-64 probe files are committed.
$fresh = (($lines -join "`n") -replace "`r", "").TrimEnd("`n") + "`n"

Write-Host "== freshly probed on this ARM64 runner =="
Write-Host $fresh

if ($fresh -notmatch '(?m)^\s*:arch\s+:aarch64\s*$') {
  throw "Probe-WindowsArm64.ps1: the probe did not report :arch :aarch64; this is not ARM64 evidence"
}
if ($fresh -notmatch '(?m)^\{:os\s+:windows\s*$') {
  throw "Probe-WindowsArm64.ps1: the probe did not report :os :windows"
}

$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) {
  $null = New-Item -ItemType Directory -Path $outDir -Force
}
# -NoNewline keeps the string exactly as built; the trailing LF is already in it.
[System.IO.File]::WriteAllText($OutFile, $fresh, (New-Object System.Text.UTF8Encoding $false))

if (-not (Test-Path $Committed)) {
  Write-Host ""
  Write-Host "NEW: no committed tools/probed/windows-aarch64.edn to compare against."
  Write-Host "     Review the facts above and commit them before this lane can gate."
  throw "Probe-WindowsArm64.ps1: no committed Windows ARM64 evidence exists yet"
}

$committedText = ((Get-Content -Raw -Path $Committed) -replace "`r", "").TrimEnd("`n") + "`n"

if ($committedText -eq $fresh) {
  Write-Host "OK:  windows-aarch64.edn matches this machine's real headers byte-for-byte"
  exit 0
}

Write-Host ""
Write-Host "DRIFT: the committed Windows ARM64 facts disagree with this machine's headers"
$freshLines = $fresh -split "`n"
$commLines = $committedText -split "`n"
$max = [Math]::Max($freshLines.Count, $commLines.Count)
for ($i = 0; $i -lt $max; $i++) {
  $a = if ($i -lt $commLines.Count) { $commLines[$i] } else { '<absent>' }
  $b = if ($i -lt $freshLines.Count) { $freshLines[$i] } else { '<absent>' }
  if ($a -ne $b) {
    Write-Host ("  line {0}:" -f ($i + 1))
    Write-Host ("    committed: {0}" -f $a)
    Write-Host ("    probed:    {0}" -f $b)
  }
}
throw "Probe-WindowsArm64.ps1: committed Windows ARM64 descriptor evidence is stale"
