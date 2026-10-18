@echo off
chcp 65001 > nul
title SMART CLASSROOM - AI YOLO11 SERVER
echo ============================================================
echo   SMART CLASSROOM - HE THONG CAMERA AI (YOLO11)
echo   * Che do: CHO LENH BAT WEBCAM TU GIAO DIEN WEB
echo   * Video Stream: http://localhost:5000/video_feed
echo   * Cloud Web:    https://smart-classroom-pyvl.onrender.com
echo ============================================================
echo.
cd /d "%~dp0"
python ai_server.py
pause
