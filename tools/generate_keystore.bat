@echo off
title ParamAuto - Generate Release Signing Keystore
echo ========================================================
echo   ParamAuto - Release Signing Keystore Generator
echo ========================================================
echo.
echo This script will generate a release keystore (release.keystore)
echo and create a local keystore.properties file for Gradle.
echo.
echo NOTE: Keep release.keystore and keystore.properties safe and private!
echo Both are git-ignored and must NEVER be committed to source control.
echo ========================================================
echo.

set /p KEY_PASS="Enter password for keystore and key: "
if "%KEY_PASS%"=="" (
    echo [ERROR] Password cannot be empty.
    pause
    exit /b 1
)

set /p DNAME_NAME="Enter your First and Last Name (e.g. John Doe): "
set /p DNAME_ORG="Enter your Organization Name (or press Enter for ParamPilot): "
if "%DNAME_ORG%"=="" set DNAME_ORG=ParamPilot

set DNAME="CN=%DNAME_NAME%, OU=Mobile, O=%DNAME_ORG%, L=Unknown, ST=Unknown, C=US"

echo.
echo Generating release.keystore...
keytool -genkeypair -v ^
  -keystore release.keystore ^
  -alias parampilot ^
  -keyalg RSA ^
  -keysize 2048 ^
  -validity 10000 ^
  -storepass %KEY_PASS% ^
  -keypass %KEY_PASS% ^
  -dname %DNAME%

if %errorlevel% neq 0 (
    echo [ERROR] Keystore generation failed. Ensure keytool is installed and in your PATH.
    pause
    exit /b %errorlevel%
)

echo.
echo Generating keystore.properties...
(
    echo storeFile=release.keystore
    echo storePassword=%KEY_PASS%
    echo keyAlias=parampilot
    echo keyPassword=%KEY_PASS%
) > keystore.properties

echo.
echo ========================================================
echo   SUCCESS!
echo   Created: release.keystore
echo   Created: keystore.properties
echo.
echo   You can now build signed release bundles:
echo     .\gradlew.bat bundleRelease
echo.
echo   Or signed release APKs:
echo     .\gradlew.bat assembleRelease
echo ========================================================
pause
