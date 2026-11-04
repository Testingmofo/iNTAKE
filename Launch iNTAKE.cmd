@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\iNTAKE-Launcher.ps1" %*
set "INTAKE_EXIT=%ERRORLEVEL%"

if not "%INTAKE_EXIT%"=="0" (
    echo.
    echo iNTAKE could not be started. Review the message above or launcher-error.log.
    pause
)

exit /b %INTAKE_EXIT%
