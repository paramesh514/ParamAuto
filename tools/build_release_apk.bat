@echo off
title ParamAuto - Build Release APK (.apk)
echo ========================================================
echo   ParamAuto - Build Release APK (.apk)
echo ========================================================
echo.

if not exist "keystore.properties" (
    echo [WARNING] keystore.properties not found!
    echo The APK will be built unsigned.
    echo To build a signed release APK, run tools\generate_keystore.bat first.
    echo.
)

echo Running: .\gradlew.bat assembleRelease ...
echo.
call .\gradlew.bat assembleRelease

if %errorlevel% neq 0 (
    echo [ERROR] Build failed! Check the Gradle errors above.
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================================
echo   SUCCESS! Release APK generated at:
echo   app\build\outputs\apk\release\
echo ========================================================
echo.
pause
