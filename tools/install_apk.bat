@echo off
title ParamAuto - Install APK for Android Auto
echo ========================================================
echo   ParamAuto - Install APK with Android Auto Support
echo ========================================================
echo.

set ADB="C:\Users\bogal\AppData\Local\Android\Sdk\platform-tools\adb.exe"

set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK_PATH%" (
    if exist "ParamAuto.apk" (
        set APK_PATH=ParamAuto.apk
    ) else if exist "app\build\outputs\apk\release\app-release.apk" (
        set APK_PATH=app\build\outputs\apk\release\app-release.apk
    ) else (
        echo [ERROR] No APK found! Build the project first using .\gradlew.bat assembleDebug
        pause
        exit /b 1
    )
)

echo Installing %APK_PATH% with Google Play Store installer provenance...
%ADB% install -r -i com.android.vending "%APK_PATH%"

if %errorlevel% neq 0 (
    echo [ERROR] Installation failed! Make sure device is connected with USB debugging enabled.
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================================
echo   SUCCESS! ParamPilot Nav installed successfully.
echo   Android Auto validator will accept the app.
echo ========================================================
echo.
pause

