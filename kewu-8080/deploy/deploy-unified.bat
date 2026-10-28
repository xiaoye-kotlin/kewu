@echo off
rem Deploy unified backend (one port 8080). Run as Administrator.
cd /d %~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-unified.ps1"
