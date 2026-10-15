@echo off
title Smart Classroom AI Server (YOLO11)
chcp 65001 >nul
cd /d "%~dp0"

echo =========================================================
echo    SMART CLASSROOM - MÁY CHỦ AI STREAMING (YOLO11)
echo =========================================================
echo.
echo [1/3] Luong Video Stream: http://localhost:5000/video_feed
echo [2/3] API Dieu khien:     http://localhost:5000/api/status
echo [3/3] Ket noi Spring Boot: http://localhost:8080
echo.
echo He thong san sang cho phep:
echo  - Bat/Tat Webcam truc tiep tu Giao dien Web
echo  - Tai len Video (.mp4, .avi) tu Giao dien Web
echo =========================================================
echo.

python ai_server.py

pause