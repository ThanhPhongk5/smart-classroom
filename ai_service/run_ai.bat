@echo off
chcp 65001 > nul
title SMART CLASSROOM - NODE DIEU PHOI PHONG HOC (COORDINATOR - IOT LEVEL 5)
echo ====================================================================
echo       SMART CLASSROOM - NODE DIEU PHOI PHONG HOC (IOT CAP DO 5)
echo ====================================================================
echo.
set ROOM_INPUT=%1
if "%ROOM_INPUT%"=="" (
    set /p ROOM_INPUT="Nhap ma phong hoc cho may nay (Nhan ENTER de mac dinh A101): "
)
if "%ROOM_INPUT%"=="" set ROOM_INPUT=A101
echo.
echo [XAC NHAN] May nay la Node Dieu Phoi (Coordinator) cho phong: %ROOM_INPUT%
echo [INFO] Ket noi Serial ESP32 (qua cap USB) + Camera AI YOLO
echo [INFO] Dong goi Telemetry phong %ROOM_INPUT% gui len Cloud Render
echo.
cd /d "%~dp0"
if exist "ai_service\ai_server.py" (
    cd /d "%~dp0ai_service"
)
python ai_server.py --room %ROOM_INPUT%
pause
