@echo off
rem Merge duplicate class k691400 into k206700. Backs up first, asks to type MERGE.
cd /d %~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0merge-dup-class.ps1"
