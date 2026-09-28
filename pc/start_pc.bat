@echo off
rem Windows entry point for the USBCam receiver.
setlocal
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo Python not found. Install Python 3.10+ first.
  pause
  exit /b 1
)
python receiver.py %*
