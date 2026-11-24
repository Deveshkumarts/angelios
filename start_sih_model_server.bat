@echo off
title iTANTRA - Edge AI Base Station (IndicTrans2 / IndicConformer / IndicF5)
cd /d "%~dp0backend"
echo ======================================================================
echo iTANTRA: Tactical Mesh Walkie-Talkie Base Station AI Server
echo Serving the app API on port 5000 (set ITANTRA_PORT to change)
echo Phones on the same Wi-Fi / hotspot find it automatically via the gateway,
echo or enter this PC's IP in the app's edge server settings.
echo ======================================================================
python edge_server.py
pause
