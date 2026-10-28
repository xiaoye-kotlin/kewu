@echo off
rem Wipe course-related TEST data (keeps user accounts). Backs up first, asks to type DELETE.
cd /d %~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cleanup-test-data.ps1"
