@echo off
title ParamAuto - Build Release App Bundle (.aab)
echo ========================================================
echo   ParamAuto - Build Release App Bundle (.aab)
echo ========================================================
echo.

if not exist "keystore.properties" (
    echo [WARNING] keystore.properties not found!
    echo The bundle will be built unsigned.
    echo To build a signed release bundle, run tools\generate_keystore.bat first.
    echo.
)

echo Running: .\gradlew.bat bundleRelease ...
echo.
call .\gradlew.bat bundleRelease

if %errorlevel% neq 0 (
    echo [ERROR] Build failed! Check the Gradle errors above.
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================================
echo   SUCCESS! Release App Bundle generated at:
echo   app\build\outputs\bundle\release\app-release.aab
echo ========================================================
echo.
pause
