@echo off
chcp 65001 > nul
title SMART CLASSROOM - MAY TRAM AI CAMERA
echo ============================================================
echo       SMART CLASSROOM - MAY TRAM CAMERA AI THEO PHONG
echo ============================================================
echo.
set ROOM_INPUT=%1
if "%ROOM_INPUT%"=="" (
    set /p ROOM_INPUT="Nhap ma phong hoc cho may nay (Nhan ENTER de mac dinh A101): "
)
if "%ROOM_INPUT%"=="" set ROOM_INPUT=A101
echo.
echo [XAC NHAN] May nay se phu trach Camera AI cho phong: %ROOM_INPUT%
echo [INFO] Chi khi tren Web bat camera phong %ROOM_INPUT% thi webcam may nay moi mo.
echo.
cd /d "%~dp0"
if exist "ai_service\ai_server.py" (
    cd /d "%~dp0ai_service"
)
python ai_server.py --room %ROOM_INPUT%
pause
