@echo off
setlocal

cd /d "%~dp0"

echo ============================================
echo ClusterStat - One Click Launcher
echo ============================================
echo.

echo [1/5] Compiling Java files...
javac Server\Server.java Client\Client.java
if errorlevel 1 (
    echo.
    echo Compilation failed. Fix errors and run again.
    pause
    exit /b 1
)

echo [2/5] Cleaning old listeners on ports 8080 and 1234...
for /f "tokens=5" %%p in ('netstat -aon ^| findstr ":8080" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>&1
for /f "tokens=5" %%p in ('netstat -aon ^| findstr ":1234" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>&1

echo [3/5] Starting Server...
start "ClusterStat Server" cmd /k "cd /d "%~dp0" && java Server.Server"

timeout /t 2 >nul

echo [4/5] Opening Dashboard...
start "" "http://localhost:8080/"

echo [5/5] Starting Clients...
set /p clients=How many client windows do you want to start? [default: 1]: 
if "%clients%"=="" set clients=1

for /l %%i in (1,1,%clients%) do (
    start "ClusterStat Client %%i" cmd /k "cd /d "%~dp0" && java Client.Client"
)

echo.
echo Done. Server, dashboard, and clients are running.
echo To stop everything, close the opened terminal windows.
echo.
pause
