@echo off
setlocal enabledelayedexpansion

REM Run DAS Gateway fat JAR (Windows)

set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%das-gateway-0.0.1-SNAPSHOT.jar

if not exist "%JAR_PATH%" (
  echo [ERROR] JAR not found: %JAR_PATH%
  echo.
  echo Place the fat JAR in the same folder as this .bat file.
  echo.
  echo Build it (from repo root) then copy it here:
  echo   mvnw.cmd -pl das-gateway -am -DskipTests package
  echo   copy das-gateway\target\das-gateway-0.0.1-SNAPSHOT.jar .
  echo.
  pause
  exit /b 1
)

echo Starting DAS Gateway...
echo   %JAR_PATH%
echo.

java -jar "%JAR_PATH%"

if errorlevel 1 (
  echo.
  echo [ERROR] das-gateway exited with error code %errorlevel%.
)

pause
endlocal
