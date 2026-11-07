@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-RedaApp.ps1" %*
if errorlevel 1 (
    echo.
    echo Reda_App n'a pas pu demarrer. Lisez le message ci-dessus.
    pause
)
