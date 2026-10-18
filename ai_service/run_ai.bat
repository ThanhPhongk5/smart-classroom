@echo off
chcp 65001 > nul
title SMART CLASSROOM - AI YOLO11 SERVER
echo ============================================================
echo   SMART CLASSROOM - HE THONG CAMERA AI (YOLO11)
echo   * Tu dong kich hoat Webcam laptop ca nhan
echo   * Nhan dien Hanh vi, Gio tay, Phat hien Dien thoai
echo   * Video Stream truc tiep: http://localhost:5000/video_feed
echo   * Ket noi Cloud Dashboard: https://smart-classroom-pyvl.onrender.com
echo ============================================================
echo.
cd /d "%~dp0"
python ai_server.py
pause
