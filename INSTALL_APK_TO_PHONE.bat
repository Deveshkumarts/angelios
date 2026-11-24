@echo off
echo ===================================================
echo   Installing Updated Angelios.apk to Connected Phone
echo ===================================================
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

if not exist %ADB% (
    echo Error: ADB not found at %ADB%
    pause
    exit /b 1
)

echo Checking for connected devices...
%ADB% devices

echo.
rem Prefer a freshly built APK; fall back to a local Angelios.apk (no longer stored in git)
set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" set APK=%~dp0Angelios.apk
if not exist "%APK%" (
    echo Error: no APK found. Build one first:  gradlew.bat assembleDebug
    pause
    exit /b 1
)
echo Installing %APK%...
%ADB% install -r "%APK%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===================================================
    echo [SUCCESS] Angelios.apk successfully updated on phone!
    echo ===================================================
) else (
    echo.
    echo [NOTE] If installation failed, make sure:
    echo   1. Phone is connected via USB.
    echo   2. "USB Debugging" is enabled in Developer Options.
    echo   3. Phone screen is unlocked and "Allow USB Debugging" accepted.
)
echo.
pause
