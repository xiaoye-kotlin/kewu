@echo off
rem Deploy new 8080 build (smoke test on 8090, auto-restore on failure). Run as Administrator.
cd /d %~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-8080.ps1"
