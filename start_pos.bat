@echo off
setlocal

REM Start AIROS backend (8000), edge (18000) and POS UI without hub.

echo Starting AIROS BACKEND 8000...
start "AIROS BACKEND 8000" /D "C:\AIROS code clean\ravintola_backend" cmd /k "call venv\Scripts\activate.bat && python run_backend.py"

echo Starting AIROS EDGE 18000...
start "AIROS EDGE 18000" /D "C:\AIROS code clean\AIROS POS" cmd /k "py -m uvicorn edge.app:create_app --factory --host 0.0.0.0 --port 18000"

echo Starting AIROS POS UI...
start "AIROS POS UI" /D "C:\AIROS code clean\AIROS POS\ui" cmd /k "npm run dev"

echo.
echo Launched:
echo   - Backend: http://192.168.8.158:8000
echo   - Edge:    http://192.168.8.158:18000
echo.
endlocal
