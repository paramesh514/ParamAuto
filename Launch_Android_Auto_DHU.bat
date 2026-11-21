@echo off
title ParamPilot - Android Auto Desktop Head Unit (DHU)
echo ========================================================
echo   ParamPilot - Android Auto Desktop Head Unit Launcher
echo ========================================================
echo.

echo 1. Forwarding Android Auto Head Unit Server port 5277...
"C:\Users\bogal\AppData\Local\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
if %errorlevel% neq 0 (
    echo [ERROR] Failed to forward port 5277 via ADB. Make sure your phone is connected with USB debugging enabled.
    pause
    exit /b %errorlevel%
)
echo    Port 5277 forwarded successfully!
echo.

echo 2. Launching Desktop Head Unit (1280x720, 60fps)...
cd /d "C:\Users\bogal\AppData\Local\Android\Sdk\extras\google\auto"
start "" "desktop-head-unit.exe" -c config\default_720p.ini

echo.
echo ========================================================
echo   DHU is now running! 
echo   Android Auto head unit display will project from phone.
echo ========================================================
echo.
pause
