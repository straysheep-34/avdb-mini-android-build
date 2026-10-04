@echo off
setlocal
cd /d "%~dp0"
echo ===============================================
echo   AVDB Mini Android Gecko - One-click builder
echo ===============================================
echo.
echo Java is NOT required to be preinstalled.
echo If Java 17+ is missing, a private JDK 21 will be downloaded automatically.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1"
if errorlevel 1 (
  echo.
  echo Build failed. Read the error above.
  pause
  exit /b 1
)
echo.
echo APK created successfully.
pause
