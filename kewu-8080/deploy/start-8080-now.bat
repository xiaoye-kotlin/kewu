@echo off
rem Emergency restore: start MySQL (if down) and trigger the 8080 backend task.
net start KewuMysql >nul 2>&1
sc start KewuMysql >nul 2>&1
schtasks /change /tn KewuBackend8080 /enable >nul 2>&1
schtasks /run /tn KewuBackend8080
echo Backend start requested. Wait 2-6 minutes, then check http://127.0.0.1:8080/api/health
