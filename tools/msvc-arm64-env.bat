@echo off
:: Select an MSVC toolchain that TARGETS ARM64, and fail loudly if there is not
:: one, rather than silently leaving whatever compiler is on PATH in place.
::
:: This matters more than it looks. The windows-11-vs2026-arm image also carries
:: an x86_64 MinGW gcc, and Windows-on-ARM will happily execute x64 binaries
:: under emulation. A build that "worked" without selecting a toolchain would
:: therefore produce a perfectly functional x86_64 probe on an ARM64 host and
:: report x86-64 facts labelled as ARM64 evidence. Callers must additionally
:: check the produced binary with tools\assert-arm64-image.bat: this file
:: selects the intended toolchain, that one proves what actually came out.
::
:: `arm64` is the native host-arm64/target-arm64 toolchain and is preferred.
:: `x64_arm64` is the x64-hosted cross toolchain, which runs under emulation but
:: still emits ARM64 code; it is accepted as a fallback because it is a correct
:: way to reach the same output, and the image-level assertion is what makes
:: either choice safe.

setlocal enabledelayedexpansion

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
  echo msvc-arm64-env: vswhere.exe not found; cannot locate Visual Studio.
  exit /b 1
)

set "VSPATH="
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -property installationPath 2^>nul`) do set "VSPATH=%%i"
if not defined VSPATH (
  echo msvc-arm64-env: vswhere found no Visual Studio installation.
  exit /b 1
)

set "VCVARSALL=%VSPATH%\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "%VCVARSALL%" (
  echo msvc-arm64-env: vcvarsall.bat not found under "%VSPATH%".
  exit /b 1
)

endlocal & set "VCVARSALL=%VCVARSALL%"

echo msvc-arm64-env: using "%VCVARSALL%"
call "%VCVARSALL%" arm64
if errorlevel 1 (
  echo msvc-arm64-env: native arm64 toolchain unavailable, trying x64_arm64 cross.
  call "%VCVARSALL%" x64_arm64
)
if errorlevel 1 (
  echo msvc-arm64-env: no ARM64-targeting MSVC toolchain could be selected.
  exit /b 1
)

where cl >nul 2>&1
if errorlevel 1 (
  echo msvc-arm64-env: cl.exe is not on PATH after vcvarsall.
  exit /b 1
)
echo msvc-arm64-env: VSCMD_ARG_TGT_ARCH=%VSCMD_ARG_TGT_ARCH% VSCMD_ARG_HOST_ARCH=%VSCMD_ARG_HOST_ARCH%
if not "%VSCMD_ARG_TGT_ARCH%" == "arm64" (
  echo msvc-arm64-env: selected toolchain targets "%VSCMD_ARG_TGT_ARCH%", not arm64.
  exit /b 1
)
exit /b 0
