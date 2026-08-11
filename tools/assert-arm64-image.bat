@echo off
:: Prove that %1 is a native ARM64 PE image, by reading its own header.
::
:: This is the one architecture witness in the whole ARM64 lane that does not
:: come from a process describing itself. A runner label can be wrong, a
:: toolchain selection can silently fall back, and an emulated x64 process on
:: Windows-on-ARM reports x64 while running on an ARM machine -- but the COFF
:: machine field says what the file actually is.
::
:: dumpbin prints "AA64 machine (ARM64)" for ARM64 and "AA64 machine (ARM64EC)"
:: for ARM64EC, which is an x64-compatible ABI and NOT what this lane claims to
:: be testing. Matching on the trailing parenthesis is what separates them, so
:: do not "simplify" the search string to ARM64.
::
:: Requires an MSVC environment on PATH: call tools\msvc-arm64-env.bat first.

if "%~1" == "" (
  echo assert-arm64-image: usage: assert-arm64-image.bat ^<image^>
  exit /b 2
)
if not exist "%~1" (
  echo assert-arm64-image: no such file: %~1
  exit /b 2
)

where dumpbin >nul 2>&1
if errorlevel 1 (
  echo assert-arm64-image: dumpbin.exe not on PATH; run tools\msvc-arm64-env.bat first.
  exit /b 2
)

echo assert-arm64-image: checking %~1
dumpbin /nologo /headers "%~1" | findstr /C:"machine ("
dumpbin /nologo /headers "%~1" | findstr /C:"machine (ARM64)" >nul
if errorlevel 1 (
  echo assert-arm64-image: %~1 is NOT a native ARM64 image.
  exit /b 1
)
dumpbin /nologo /headers "%~1" | findstr /C:"machine (x64)" >nul
if not errorlevel 1 (
  echo assert-arm64-image: %~1 also reports an x64 machine type.
  exit /b 1
)
echo assert-arm64-image: %~1 is a native ARM64 image.
exit /b 0
