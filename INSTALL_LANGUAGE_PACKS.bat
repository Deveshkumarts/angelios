@echo off
setlocal
title Angelios - install on-device speech packs
rem Copies the open-source speech models (IndicConformer STT + MMS TTS + Silero VAD) from
rem models\packs to the phone over USB. No internet is used on the phone.
rem
rem   INSTALL_LANGUAGE_PACKS.bat            -> every language downloaded on this PC
rem   INSTALL_LANGUAGE_PACKS.bat hi ta en   -> only those languages
rem
rem Download packs first with:  python tools\download_language_packs.py hi ta en   (or: all)

set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set PACKS=%~dp0models\packs
set DEST=/sdcard/Android/data/org.sih.itantra/files/packs
rem Android 11+ refuses direct "adb push" writes into Android/data, so files are staged in
rem /data/local/tmp and copied into place on the phone.
set STAGE=/data/local/tmp/angelios_packs

if not exist %ADB% (
    echo Error: ADB not found at %ADB%
    pause
    exit /b 1
)
if not exist "%PACKS%\common\silero_vad.onnx" (
    echo No speech packs on this PC yet. Run first:
    echo     python tools\download_language_packs.py hi ta en
    pause
    exit /b 1
)

echo Connected devices:
%ADB% devices
echo Install and open Angelios once on the phone before running this script.
%ADB% shell mkdir -p %DEST% %STAGE%
call :push common

if "%~1"=="" (
    for /d %%L in ("%PACKS%\*") do if /i not "%%~nxL"=="common" call :push %%~nxL
) else (
    for %%L in (%*) do call :push %%L
)

echo.
echo Done. Open Angelios ^> ME ^> On-device speech packs to check which languages are ready.
pause
exit /b 0

:push
if /i not "%1"=="common" if not exist "%PACKS%\%1\pack.json" (
    echo Skipping %1: not downloaded on this PC
    exit /b 0
)
if /i not "%1"=="common" echo Copying %1 (about 300 MB, takes a minute over USB)...
%ADB% push "%PACKS%\%1" %STAGE%/
%ADB% shell "cp -r %STAGE%/%1 %DEST%/ && rm -rf %STAGE%/%1"
exit /b 0
